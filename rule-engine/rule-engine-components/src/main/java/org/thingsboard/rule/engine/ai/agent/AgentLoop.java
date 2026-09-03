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

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.server.common.data.ai.AiModel;
import org.thingsboard.server.common.data.ai.model.AiModelType;
import org.thingsboard.server.common.data.ai.model.chat.AiChatModelConfig;
import org.thingsboard.server.common.data.id.AiModelId;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

/**
 * The agentic loop itself: think -> call instruments -> observe -> think again, until the model stops
 * asking for tools or the iteration budget runs out.
 * <p>
 * The loop is fully asynchronous (no blocking on rule engine threads) and is driven by chained callbacks
 * rather than recursion on a single stack.
 */
@Slf4j
@Builder
public class AgentLoop {

    private final AiModelId modelId;
    private final int timeoutSeconds;
    private final int maxIterations;
    private final AgentToolRegistry tools;

    /**
     * @param finalAnswer          the last text produced by the model, may be {@code null} if it kept asking for tools
     * @param messages             full conversation produced by this run (to be appended to the session history)
     * @param iterations           how many LLM round trips this run took
     * @param toolCalls            names of the instruments that were actually executed, in order
     * @param stoppedOnIterationLimit whether the loop was cut short by {@code maxIterations}
     */
    public record Result(String finalAnswer,
                         List<ChatMessage> messages,
                         int iterations,
                         List<String> toolCalls,
                         boolean stoppedOnIterationLimit) {}

    public ListenableFuture<Result> run(AgentToolContext toolCtx, AgentDebugLogger debug, List<ChatMessage> messages) {
        SettableFuture<Result> resultFuture = SettableFuture.create();
        step(toolCtx, debug, new ArrayList<>(messages), new ArrayList<>(), 0, resultFuture);
        return resultFuture;
    }

    private void step(AgentToolContext toolCtx, AgentDebugLogger debug, List<ChatMessage> messages,
                      List<String> toolCalls, int iteration, SettableFuture<Result> resultFuture) {
        if (iteration >= maxIterations) {
            log.debug("Agent loop stopped after reaching the iteration limit of {}", maxIterations);
            resultFuture.set(new Result(lastText(messages), messages, iteration, toolCalls, true));
            return;
        }

        ChatRequest.Builder requestBuilder = ChatRequest.builder().messages(messages);
        if (tools != null && !tools.isEmpty()) {
            requestBuilder.toolSpecifications(tools.specifications());
        }
        ChatRequest request = requestBuilder.build();

        final int currentIteration = iteration + 1;
        debug.logRequest(currentIteration, request);

        Futures.addCallback(sendChatRequestAsync(toolCtx.ctx(), request), new FutureCallback<>() {
            @Override
            public void onSuccess(ChatResponse chatResponse) {
                try {
                    debug.logResponse(currentIteration, chatResponse);
                    AiMessage aiMessage = chatResponse.aiMessage();
                    messages.add(aiMessage);

                    if (!aiMessage.hasToolExecutionRequests()) {
                        resultFuture.set(new Result(aiMessage.text(), messages, currentIteration, toolCalls, false));
                        return;
                    }

                    List<ListenableFuture<ToolExecutionResultMessage>> toolFutures = new ArrayList<>();
                    for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
                        toolCalls.add(toolRequest.name());
                        toolFutures.add(executeTool(toolCtx, debug, currentIteration, toolRequest));
                    }

                    Futures.addCallback(Futures.allAsList(toolFutures), new FutureCallback<>() {
                        @Override
                        public void onSuccess(List<ToolExecutionResultMessage> results) {
                            messages.addAll(results);
                            step(toolCtx, debug, messages, toolCalls, currentIteration, resultFuture);
                        }

                        @Override
                        public void onFailure(Throwable t) {
                            debug.logError(currentIteration, t);
                            resultFuture.setException(t);
                        }
                    }, directExecutor());
                } catch (Throwable t) {
                    debug.logError(currentIteration, t);
                    resultFuture.setException(t);
                }
            }

            @Override
            public void onFailure(Throwable t) {
                debug.logError(currentIteration, t);
                resultFuture.setException(t);
            }
        }, directExecutor());
    }

    /**
     * Instrument failures are never fatal: the error is handed back to the model as a tool result,
     * which lets it retry with different arguments or explain the failure in the final answer.
     */
    private ListenableFuture<ToolExecutionResultMessage> executeTool(AgentToolContext toolCtx, AgentDebugLogger debug,
                                                                    int iteration, ToolExecutionRequest toolRequest) {
        AgentTool tool = tools == null ? null : tools.get(toolRequest.name());
        if (tool == null) {
            String error = JacksonUtil.newObjectNode()
                    .put("error", "Unknown tool '" + toolRequest.name() + "'. Available tools: " +
                            (tools == null ? "none" : String.join(", ", tools.names())))
                    .toString();
            debug.logToolCall(iteration, toolRequest.name(), toolRequest.arguments(), error, null);
            return Futures.immediateFuture(ToolExecutionResultMessage.from(toolRequest, error));
        }
        try {
            JsonNode args = parseArguments(toolRequest.arguments());
            return Futures.catching(
                    Futures.transform(
                            tool.execute(toolCtx, args),
                            result -> {
                                debug.logToolCall(iteration, toolRequest.name(), toolRequest.arguments(), result, null);
                                return ToolExecutionResultMessage.from(toolRequest, result);
                            },
                            MoreExecutors.directExecutor()),
                    Throwable.class,
                    t -> {
                        String error = JacksonUtil.newObjectNode().put("error", String.valueOf(t.getMessage())).toString();
                        debug.logToolCall(iteration, toolRequest.name(), toolRequest.arguments(), error, t);
                        return ToolExecutionResultMessage.from(toolRequest, error);
                    },
                    MoreExecutors.directExecutor());
        } catch (Exception e) {
            String error = JacksonUtil.newObjectNode().put("error", "Failed to execute tool: " + e.getMessage()).toString();
            debug.logToolCall(iteration, toolRequest.name(), toolRequest.arguments(), error, e);
            return Futures.immediateFuture(ToolExecutionResultMessage.from(toolRequest, error));
        }
    }

    private static JsonNode parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return JacksonUtil.newObjectNode();
        }
        JsonNode node = JacksonUtil.toJsonNode(arguments);
        return node == null || !node.isObject() ? JacksonUtil.newObjectNode() : node;
    }

    private static String lastText(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AiMessage aiMessage && aiMessage.text() != null) {
                return aiMessage.text();
            }
        }
        return null;
    }

    private <C extends AiChatModelConfig<C>> FluentFuture<ChatResponse> sendChatRequestAsync(TbContext ctx, ChatRequest chatRequest) {
        return ctx.getAiModelService().findAiModelByTenantIdAndIdAsync(ctx.getTenantId(), modelId).transformAsync(modelOpt -> {
            if (modelOpt.isEmpty()) {
                throw new NoSuchElementException("[" + ctx.getTenantId() + "] AI model with ID: [" + modelId + "] was not found");
            }
            AiModel model = modelOpt.get();
            AiModelType modelType = model.getConfiguration().modelType();
            if (modelType != AiModelType.CHAT) {
                throw new IllegalStateException("[" + ctx.getTenantId() + "] AI model with ID: [" + modelId + "] must be of type CHAT, but was " + modelType);
            }

            @SuppressWarnings("unchecked")
            AiChatModelConfig<C> chatModelConfig = (AiChatModelConfig<C>) model.getConfiguration();

            chatModelConfig = chatModelConfig
                    .withTimeoutSeconds(timeoutSeconds)
                    .withMaxRetries(0);

            return ctx.getAiChatModelService().sendChatRequestAsync(chatModelConfig, chatRequest);
        }, ctx.getDbCallbackExecutor());
    }

}
