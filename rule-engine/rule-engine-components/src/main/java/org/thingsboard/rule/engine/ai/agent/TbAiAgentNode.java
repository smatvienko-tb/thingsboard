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
import com.google.common.util.concurrent.ListenableFuture;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.rule.engine.api.RuleNode;
import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.rule.engine.api.TbNode;
import org.thingsboard.rule.engine.api.TbNodeConfiguration;
import org.thingsboard.rule.engine.api.TbNodeException;
import org.thingsboard.rule.engine.api.util.TbNodeUtils;
import org.thingsboard.rule.engine.external.TbAbstractExternalNode;
import org.thingsboard.server.common.data.ai.AiModel;
import org.thingsboard.server.common.data.ai.model.AiModelType;
import org.thingsboard.server.common.data.asset.Asset;
import org.thingsboard.server.common.data.asset.AssetProfile;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.plugin.ComponentType;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.exception.DataValidationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.thingsboard.server.dao.service.ConstraintValidator.validateFields;

@Slf4j
@RuleNode(
        type = ComponentType.EXTERNAL,
        name = "AI agent",
        nodeDescription = "Runs an agentic loop against an AI model: the model may call platform tools before answering.",
        nodeDetails = """
                Unlike the <strong>AI request</strong> node, which performs a single call, this node runs a
                <strong>think - act - observe</strong> loop. The model is given a set of read-only <strong>tools</strong>
                (attributes, latest telemetry, telemetry history, related entities) plus two memory tools
                (<code>recall</code> / <code>remember</code>), and keeps calling them until it can answer,
                or until the configured <strong>max iterations</strong> budget is exhausted.
                <br><br>
                Session state and long-term memory are stored as <code>SERVER_SCOPE</code> attributes of a configurable
                <strong>state asset</strong>, so the whole agent session is observable and editable from the UI.
                A session is reused across messages and expires after the configured inactivity timeout.
                <br><br>
                Every AI call, every tool invocation and every tool result is written to the rule node debug events
                unconditionally, so an agentic run can always be reconstructed after the fact.
                <br><br>
                Output connections: <code>Success</code>, <code>Failure</code>.
                """,
        configClazz = TbAiAgentNodeConfiguration.class,
        configDirective = "tbExternalNodeAiAgentConfig",
        docUrl = "https://thingsboard.io/docs/reference/rule-engine/nodes/external/ai-agent/"
)
public final class TbAiAgentNode extends TbAbstractExternalNode implements TbNode {

    private TbAiAgentNodeConfiguration config;
    private AgentStateStore stateStore;
    private AgentToolRegistry tools;
    private AgentLoop loop;
    private long sessionTtlMillis;

    @Override
    public void init(TbContext ctx, TbNodeConfiguration configuration) throws TbNodeException {
        super.init(ctx);

        config = TbNodeUtils.convert(configuration, TbAiAgentNodeConfiguration.class);
        String errorPrefix = "'" + ctx.getSelf().getName() + "' node configuration is invalid: ";
        try {
            validateFields(config, errorPrefix);
        } catch (DataValidationException e) {
            throw new TbNodeException(e, true);
        }

        Optional<AiModel> modelOpt = ctx.getAiModelService().findAiModelByTenantIdAndId(ctx.getTenantId(), config.getModelId());
        if (modelOpt.isEmpty()) {
            throw new TbNodeException("[" + ctx.getTenantId() + "] AI model with ID: [" + config.getModelId() + "] was not found", true);
        }
        AiModelType modelType = modelOpt.get().getConfiguration().modelType();
        if (modelType != AiModelType.CHAT) {
            throw new TbNodeException("[" + ctx.getTenantId() + "] AI model with ID: [" + config.getModelId() +
                    "] must be of type CHAT, but was " + modelType, true);
        }

        stateStore = new AgentStateStore();
        tools = new AgentToolRegistry(config.getEnabledTools(), stateStore);
        loop = AgentLoop.builder()
                .modelId(config.getModelId())
                .timeoutSeconds(config.getTimeoutSeconds())
                .maxIterations(config.getMaxIterations())
                .tools(tools)
                .build();
        sessionTtlMillis = config.getSessionTtlMinutes() * 60_000L;
        super.forceAck = config.isForceAck() || super.forceAck;
    }

    @Override
    public void onMsg(TbContext ctx, TbMsg msg) {
        TbMsg ackedMsg = ackIfNeeded(ctx, msg);
        long now = System.currentTimeMillis();

        ListenableFuture<Void> agentStarted = Futures.transformAsync(
                ctx.getDbCallbackExecutor().executeAsync(() -> resolveStateEntity(ctx, ackedMsg)),
                stateEntityId -> Futures.transform(
                        stateStore.load(ctx, stateEntityId, sessionTtlMillis, now),
                        state -> {
                            state.setLastActivityTs(now);
                            runAgent(ctx, ackedMsg, stateEntityId, state, now);
                            return null;
                        }, directExecutor()),
                ctx.getDbCallbackExecutor());

        Futures.addCallback(agentStarted, new FutureCallback<>() {
            @Override
            public void onSuccess(Void unused) {
                // the agent run completes asynchronously and reports on its own
            }

            @Override
            public void onFailure(Throwable t) {
                tellFailure(ctx, ackedMsg, t);
            }
        }, directExecutor());
    }

    private void runAgent(TbContext ctx, TbMsg msg, EntityId stateEntityId, AgentState state, long now) {
        var toolCtx = new AgentToolContext(ctx, msg, msg.getOriginator(), stateEntityId, config.isRestrictToolsToOriginator());
        var debug = new AgentDebugLogger(ctx, msg, state.getSessionId());

        List<ChatMessage> messages = new ArrayList<>(state.getHistory().size() + 2);
        messages.add(SystemMessage.from(TbNodeUtils.processPattern(config.getSystemPrompt(), msg)));
        messages.addAll(state.getHistory());
        messages.add(UserMessage.from(TbNodeUtils.processPattern(config.getUserPrompt(), msg)));

        Futures.addCallback(loop.run(toolCtx, debug, messages), new FutureCallback<>() {
            @Override
            public void onSuccess(AgentLoop.Result result) {
                try {
                    persistState(ctx, stateEntityId, state, result, now);
                    tellSuccess(ctx, msg.transform()
                            .data(toOutput(result, state).toString())
                            .build());
                } catch (Throwable t) {
                    tellFailure(ctx, msg, t);
                }
            }

            @Override
            public void onFailure(Throwable t) {
                tellFailure(ctx, msg, t);
            }
        }, directExecutor());
    }

    private void persistState(TbContext ctx, EntityId stateEntityId, AgentState state, AgentLoop.Result result, long now) {
        // drop the system message: it is re-rendered from the (possibly updated) config on every run
        List<ChatMessage> history = result.messages().stream()
                .filter(message -> !(message instanceof SystemMessage))
                .toList();
        state.setHistory(new ArrayList<>(history));
        state.trimTo(config.getMemoryWindowSize());
        state.setLastActivityTs(now);
        state.setTotalIterations(state.getTotalIterations() + result.iterations());
        state.setTotalRuns(state.getTotalRuns() + 1);
        stateStore.save(ctx, stateEntityId, state, result.finalAnswer(), config.isPersistConversation());
    }

    private ObjectNode toOutput(AgentLoop.Result result, AgentState state) {
        ObjectNode output = JacksonUtil.newObjectNode();
        output.put("response", result.finalAnswer());
        output.put("sessionId", state.getSessionId().toString());
        output.put("iterations", result.iterations());
        output.put("stoppedOnIterationLimit", result.stoppedOnIterationLimit());
        ArrayNode toolCalls = output.putArray("toolCalls");
        result.toolCalls().forEach(toolCalls::add);
        return output;
    }

    /**
     * Resolves (and optionally creates) the asset that holds this agent's state.
     * Blocking on purpose - it runs on the DB callback executor, not on a rule engine thread.
     */
    private EntityId resolveStateEntity(TbContext ctx, TbMsg msg) {
        String assetName = TbNodeUtils.processPattern(config.getStateAssetName(), msg);
        Asset asset = ctx.getAssetService().findAssetByTenantIdAndName(ctx.getTenantId(), assetName);
        if (asset != null) {
            return asset.getId();
        }
        if (!config.isCreateStateAssetIfMissing()) {
            throw new IllegalStateException("Agent state asset with name '" + assetName + "' was not found");
        }
        String profileName = config.getStateAssetProfile() == null || config.getStateAssetProfile().isBlank()
                ? "AI Agent" : config.getStateAssetProfile();
        AssetProfile profile = ctx.getAssetProfileService().findOrCreateAssetProfile(ctx.getTenantId(), profileName);
        Asset newAsset = new Asset();
        newAsset.setTenantId(ctx.getTenantId());
        newAsset.setName(assetName);
        newAsset.setType(profile.getName());
        newAsset.setAssetProfileId(profile.getId());
        return ctx.getAssetService().saveAsset(newAsset).getId();
    }

    @Override
    public void destroy() {
        super.destroy();
        config = null;
        tools = null;
        loop = null;
        stateStore = null;
    }

}
