/**
 * Copyright © 2016-2026 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.rule.engine.ai.agent;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.server.common.data.event.RuleNodeDebugEvent;
import org.thingsboard.server.common.msg.TbMsg;

import java.util.UUID;

/**
 * Persists one debug event per AI interaction, regardless of the node debug settings.
 * <p>
 * Rationale: an agentic loop is opaque by nature - without a per-iteration trace of what was sent,
 * what came back and which instruments were called, a failing run is impossible to explain after the fact.
 * The volume is bounded by {@code maxIterations}, so "always on" is affordable here in a way it is not
 * for ordinary high-throughput nodes.
 * <p>
 * Draft note: this bypasses the tenant debug rate limits applied by {@code ActorSystemContext#checkLimits}.
 * Before this ships, the events should go through the same limiter, or the node should get its own quota.
 */
@Slf4j
@RequiredArgsConstructor
public class AgentDebugLogger {

    public static final String EVENT_TYPE_REQUEST = "AI_REQUEST";
    public static final String EVENT_TYPE_RESPONSE = "AI_RESPONSE";
    public static final String EVENT_TYPE_TOOL = "AI_TOOL_CALL";

    private static final int MAX_TEXT_LENGTH = 20_000;

    private final TbContext ctx;
    private final TbMsg msg;
    private final UUID sessionId;

    public void logRequest(int iteration, ChatRequest request) {
        ObjectNode data = JacksonUtil.newObjectNode();
        data.put("iteration", iteration);
        ArrayNode messages = data.putArray("messages");
        for (ChatMessage message : request.messages()) {
            ObjectNode node = messages.addObject();
            node.put("type", message.type().name());
            node.put("text", truncate(describe(message)));
        }
        ArrayNode tools = data.putArray("tools");
        if (request.toolSpecifications() != null) {
            request.toolSpecifications().forEach(spec -> tools.add(spec.name()));
        }
        persist(EVENT_TYPE_REQUEST, data, null);
    }

    public void logResponse(int iteration, ChatResponse response) {
        ObjectNode data = JacksonUtil.newObjectNode();
        data.put("iteration", iteration);
        AiMessage aiMessage = response.aiMessage();
        data.put("text", truncate(aiMessage.text()));
        if (response.finishReason() != null) {
            data.put("finishReason", response.finishReason().name());
        }
        if (response.tokenUsage() != null) {
            ObjectNode usage = data.putObject("tokenUsage");
            usage.put("input", response.tokenUsage().inputTokenCount());
            usage.put("output", response.tokenUsage().outputTokenCount());
            usage.put("total", response.tokenUsage().totalTokenCount());
        }
        if (aiMessage.hasToolExecutionRequests()) {
            ArrayNode calls = data.putArray("toolCalls");
            aiMessage.toolExecutionRequests().forEach(req -> {
                ObjectNode node = calls.addObject();
                node.put("id", req.id());
                node.put("name", req.name());
                node.put("arguments", truncate(req.arguments()));
            });
        }
        persist(EVENT_TYPE_RESPONSE, data, null);
    }

    public void logToolCall(int iteration, String toolName, String arguments, String result, Throwable error) {
        ObjectNode data = JacksonUtil.newObjectNode();
        data.put("iteration", iteration);
        data.put("tool", toolName);
        data.put("arguments", truncate(arguments));
        data.put("result", truncate(result));
        persist(EVENT_TYPE_TOOL, data, error == null ? null : error.getMessage());
    }

    public void logError(int iteration, Throwable error) {
        ObjectNode data = JacksonUtil.newObjectNode();
        data.put("iteration", iteration);
        persist(EVENT_TYPE_RESPONSE, data, error == null ? "unknown error" : error.toString());
    }

    private void persist(String eventType, ObjectNode data, String error) {
        try {
            data.put("sessionId", sessionId.toString());
            var event = RuleNodeDebugEvent.builder()
                    .tenantId(ctx.getTenantId())
                    .entityId(ctx.getSelfId().getId())
                    .serviceId(ctx.getServiceId())
                    .eventType(eventType)
                    .eventEntity(msg.getOriginator())
                    .msgId(msg.getId())
                    .msgType(msg.getType())
                    .dataType(msg.getDataType().name())
                    .data(data.toString())
                    .metadata(JacksonUtil.toString(msg.getMetaData().getData()))
                    .error(error)
                    .build();
            Futures.addCallback(ctx.getEventService().saveAsync(event), new FutureCallback<>() {
                @Override
                public void onSuccess(Void unused) {}

                @Override
                public void onFailure(Throwable t) {
                    log.warn("[{}] Failed to persist AI agent debug event", ctx.getTenantId(), t);
                }
            }, MoreExecutors.directExecutor());
        } catch (Exception e) {
            log.warn("[{}] Failed to build AI agent debug event", ctx.getTenantId(), e);
        }
    }

    private static String describe(ChatMessage message) {
        return switch (message.type()) {
            case SYSTEM -> ((dev.langchain4j.data.message.SystemMessage) message).text();
            case USER -> ((dev.langchain4j.data.message.UserMessage) message).singleText();
            case AI -> {
                AiMessage ai = (AiMessage) message;
                yield ai.hasToolExecutionRequests()
                        ? "[tool calls] " + ai.toolExecutionRequests()
                        : String.valueOf(ai.text());
            }
            case TOOL_EXECUTION_RESULT -> ((dev.langchain4j.data.message.ToolExecutionResultMessage) message).text();
            default -> message.toString();
        };
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_TEXT_LENGTH ? value : value.substring(0, MAX_TEXT_LENGTH) + "...[truncated]";
    }

}
