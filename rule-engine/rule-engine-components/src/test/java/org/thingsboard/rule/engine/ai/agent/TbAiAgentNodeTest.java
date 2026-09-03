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
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thingsboard.common.util.DirectListeningExecutor;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.rule.engine.api.AttributesSaveRequest;
import org.thingsboard.rule.engine.api.RuleEngineAiChatModelService;
import org.thingsboard.rule.engine.api.RuleEngineTelemetryService;
import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.rule.engine.api.TbNodeConfiguration;
import org.thingsboard.rule.engine.api.TbNodeException;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.common.data.ai.AiModel;
import org.thingsboard.server.common.data.ai.model.chat.OpenAiChatModelConfig;
import org.thingsboard.server.common.data.ai.provider.OpenAiProviderConfig;
import org.thingsboard.server.common.data.asset.Asset;
import org.thingsboard.server.common.data.id.AiModelId;
import org.thingsboard.server.common.data.id.AssetId;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.RuleNodeId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.common.data.kv.BaseAttributeKvEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.common.data.rule.RuleNode;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.TbMsgMetaData;
import org.thingsboard.server.dao.ai.AiModelService;
import org.thingsboard.server.dao.asset.AssetProfileService;
import org.thingsboard.server.dao.asset.AssetService;
import org.thingsboard.server.dao.attributes.AttributesService;
import org.thingsboard.server.dao.event.EventService;
import org.thingsboard.server.exception.DataValidationException;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.google.common.util.concurrent.Futures.immediateFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class TbAiAgentNodeTest {

    @Mock
    TbContext ctxMock;
    @Mock
    AiModelService aiModelServiceMock;
    @Mock
    RuleEngineAiChatModelService aiChatModelServiceMock;
    @Mock
    AssetService assetServiceMock;
    @Mock
    AssetProfileService assetProfileServiceMock;
    @Mock
    AttributesService attributesServiceMock;
    @Mock
    RuleEngineTelemetryService telemetryServiceMock;
    @Mock
    EventService eventServiceMock;

    TbAiAgentNode node;
    TbAiAgentNodeConfiguration config;

    TenantId tenantId = TenantId.fromUUID(UUID.randomUUID());
    DeviceId deviceId = new DeviceId(UUID.randomUUID());
    AssetId stateAssetId = new AssetId(UUID.randomUUID());
    AiModelId modelId = new AiModelId(UUID.randomUUID());
    RuleNodeId ruleNodeId = new RuleNodeId(UUID.randomUUID());

    RuleNode ruleNode;
    AiModel model;

    @BeforeEach
    void setup() {
        node = new TbAiAgentNode();
        config = new TbAiAgentNodeConfiguration().defaultConfiguration();
        config.setModelId(modelId);
        config.setStateAssetName("AI Agent State");

        model = AiModel.builder()
                .tenantId(tenantId)
                .name("Test model")
                .configuration(OpenAiChatModelConfig.builder()
                        .providerConfig(OpenAiProviderConfig.builder()
                                .baseUrl(OpenAiProviderConfig.OPENAI_OFFICIAL_BASE_URL)
                                .apiKey("test-api-key")
                                .build())
                        .modelId("gpt-4o")
                        .timeoutSeconds(60)
                        .maxRetries(0)
                        .build())
                .build();
        model.setId(modelId);

        ruleNode = new RuleNode();
        ruleNode.setId(ruleNodeId);
        ruleNode.setName("Test AI agent node");

        lenient().when(ctxMock.getSelf()).thenReturn(ruleNode);
        lenient().when(ctxMock.getSelfId()).thenReturn(ruleNodeId);
        lenient().when(ctxMock.getServiceId()).thenReturn("test-service");
        lenient().when(ctxMock.getTenantId()).thenReturn(tenantId);
        lenient().when(ctxMock.getAiModelService()).thenReturn(aiModelServiceMock);
        lenient().when(ctxMock.getAiChatModelService()).thenReturn(aiChatModelServiceMock);
        lenient().when(ctxMock.getAssetService()).thenReturn(assetServiceMock);
        lenient().when(ctxMock.getAssetProfileService()).thenReturn(assetProfileServiceMock);
        lenient().when(ctxMock.getAttributesService()).thenReturn(attributesServiceMock);
        lenient().when(ctxMock.getTelemetryService()).thenReturn(telemetryServiceMock);
        lenient().when(ctxMock.getEventService()).thenReturn(eventServiceMock);
        lenient().when(ctxMock.getDbCallbackExecutor()).thenReturn(DirectListeningExecutor.INSTANCE);

        lenient().when(aiModelServiceMock.findAiModelByTenantIdAndId(tenantId, modelId)).thenReturn(Optional.of(model));
        lenient().when(aiModelServiceMock.findAiModelByTenantIdAndIdAsync(tenantId, modelId))
                .thenReturn(FluentFuture.from(immediateFuture(Optional.of(model))));

        Asset stateAsset = new Asset();
        stateAsset.setId(stateAssetId);
        stateAsset.setTenantId(tenantId);
        stateAsset.setName("AI Agent State");
        lenient().when(assetServiceMock.findAssetByTenantIdAndName(tenantId, "AI Agent State")).thenReturn(stateAsset);

        lenient().when(eventServiceMock.saveAsync(any())).thenReturn(immediateFuture(null));
        givenNoStoredState();
    }

    // ------------------------------------------------------------------------------------------------- config

    @Test
    void givenDefaultConfig_whenCalled_thenSetsSensibleDefaults() {
        var defaults = new TbAiAgentNodeConfiguration().defaultConfiguration();

        assertThat(defaults.getModelId()).isNull();
        assertThat(defaults.getSystemPrompt()).contains("IoT operations agent");
        assertThat(defaults.getMaxIterations()).isEqualTo(10);
        assertThat(defaults.getTimeoutSeconds()).isEqualTo(60);
        assertThat(defaults.getMemoryWindowSize()).isEqualTo(40);
        assertThat(defaults.isRestrictToolsToOriginator()).isTrue();
        assertThat(defaults.isCreateStateAssetIfMissing()).isTrue();
        assertThat(defaults.getEnabledTools()).contains(AgentToolType.RECALL_MEMORY, AgentToolType.REMEMBER);
    }

    @Test
    void givenMissingModelId_whenInit_thenThrowsUnrecoverable() {
        config.setModelId(null);

        assertThatThrownBy(() -> node.init(ctxMock, configuration()))
                .isInstanceOf(TbNodeException.class)
                .matches(e -> ((TbNodeException) e).isUnrecoverable())
                .rootCause()
                .isInstanceOf(DataValidationException.class)
                .hasMessageContaining("modelId");
    }

    @Test
    void givenModelNotFound_whenInit_thenThrowsUnrecoverable() {
        given(aiModelServiceMock.findAiModelByTenantIdAndId(tenantId, modelId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> node.init(ctxMock, configuration()))
                .isInstanceOf(TbNodeException.class)
                .hasMessageContaining("was not found")
                .matches(e -> ((TbNodeException) e).isUnrecoverable());
    }

    // ---------------------------------------------------------------------------------------------- the loop

    @Test
    void givenNoToolsEnabled_whenOnMsg_thenSingleCallAndAnswerIsForwarded() throws Exception {
        config.setEnabledTools(Set.of());
        givenChatResponses(textResponse("all good"));
        node.init(ctxMock, configuration());

        node.onMsg(ctxMock, msg());

        var captor = ArgumentCaptor.forClass(TbMsg.class);
        then(ctxMock).should().tellSuccess(captor.capture());
        JsonNode output = JacksonUtil.toJsonNode(captor.getValue().getData());
        assertThat(output.get("response").asText()).isEqualTo("all good");
        assertThat(output.get("iterations").asInt()).isEqualTo(1);
        assertThat(output.get("stoppedOnIterationLimit").asBoolean()).isFalse();
        assertThat(output.get("toolCalls")).isEmpty();
        then(aiChatModelServiceMock).should(times(1)).sendChatRequestAsync(any(), any());
    }

    @Test
    void givenModelCallsTool_whenOnMsg_thenToolResultIsFedBackAndLoopContinues() throws Exception {
        config.setEnabledTools(EnumSet.of(AgentToolType.READ_LATEST_TELEMETRY));
        given(ctxMock.getTimeseriesService()).willReturn(null); // not reached: tool failure is handled gracefully
        givenChatResponses(
                toolCallResponse("read_latest_telemetry", "{\"keys\":[\"temperature\"]}"),
                textResponse("temperature looks normal"));
        node.init(ctxMock, configuration());

        node.onMsg(ctxMock, msg());

        var requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        then(aiChatModelServiceMock).should(times(2)).sendChatRequestAsync(any(), requestCaptor.capture());

        ChatRequest first = requestCaptor.getAllValues().get(0);
        assertThat(first.messages()).hasSize(2);
        assertThat(first.messages().get(0)).isInstanceOf(SystemMessage.class);
        assertThat(first.messages().get(1)).isInstanceOf(UserMessage.class);
        assertThat(first.toolSpecifications()).extracting("name").containsExactly("read_latest_telemetry");

        ChatRequest second = requestCaptor.getAllValues().get(1);
        assertThat(second.messages()).hasSize(4);
        assertThat(second.messages().get(2)).isInstanceOf(AiMessage.class);
        assertThat(second.messages().get(3)).isInstanceOf(ToolExecutionResultMessage.class);

        var captor = ArgumentCaptor.forClass(TbMsg.class);
        then(ctxMock).should().tellSuccess(captor.capture());
        JsonNode output = JacksonUtil.toJsonNode(captor.getValue().getData());
        assertThat(output.get("response").asText()).isEqualTo("temperature looks normal");
        assertThat(output.get("toolCalls").get(0).asText()).isEqualTo("read_latest_telemetry");
    }

    @Test
    void givenModelKeepsCallingTools_whenOnMsg_thenStopsOnIterationLimit() throws Exception {
        config.setEnabledTools(EnumSet.of(AgentToolType.RECALL_MEMORY));
        config.setMaxIterations(3);
        given(attributesServiceMock.findAll(eq(tenantId), eq(stateAssetId), eq(AttributeScope.SERVER_SCOPE)))
                .willReturn(immediateFuture(List.of()));
        var loopingResponse = toolCallResponse("recall", "{}");
        givenChatResponses(loopingResponse, loopingResponse, loopingResponse, loopingResponse);
        node.init(ctxMock, configuration());

        node.onMsg(ctxMock, msg());

        then(aiChatModelServiceMock).should(times(3)).sendChatRequestAsync(any(), any());
        var captor = ArgumentCaptor.forClass(TbMsg.class);
        then(ctxMock).should().tellSuccess(captor.capture());
        JsonNode output = JacksonUtil.toJsonNode(captor.getValue().getData());
        assertThat(output.get("stoppedOnIterationLimit").asBoolean()).isTrue();
        assertThat(output.get("iterations").asInt()).isEqualTo(3);
    }

    @Test
    void givenChatCallFails_whenOnMsg_thenTellFailure() throws Exception {
        config.setEnabledTools(Set.of());
        var failure = new RuntimeException("provider is down");
        given(aiChatModelServiceMock.sendChatRequestAsync(any(), any()))
                .willReturn(FluentFuture.from(com.google.common.util.concurrent.Futures.immediateFailedFuture(failure)));
        node.init(ctxMock, configuration());

        node.onMsg(ctxMock, msg());

        then(ctxMock).should().tellFailure(any(TbMsg.class), eq(failure));
    }

    // ------------------------------------------------------------------------------------------ state / memory

    @Test
    void givenSuccessfulRun_whenOnMsg_thenSessionStateIsPersistedOnStateAsset() throws Exception {
        config.setEnabledTools(Set.of());
        givenChatResponses(textResponse("done"));
        node.init(ctxMock, configuration());

        node.onMsg(ctxMock, msg());

        var captor = ArgumentCaptor.forClass(AttributesSaveRequest.class);
        then(telemetryServiceMock).should().saveAttributes(captor.capture());
        AttributesSaveRequest request = captor.getValue();
        assertThat(request.getEntityId()).isEqualTo(stateAssetId);
        assertThat(request.getScope()).isEqualTo(AttributeScope.SERVER_SCOPE);
        assertThat(request.getEntries()).extracting(AttributeKvEntry::getKey)
                .contains(AgentStateStore.SESSION_ID_KEY, AgentStateStore.HISTORY_KEY, AgentStateStore.LAST_ANSWER_KEY);
    }

    @Test
    void givenExistingSession_whenOnMsg_thenHistoryIsReplayedIntoTheRequest() throws Exception {
        config.setEnabledTools(Set.of());
        long now = System.currentTimeMillis();
        List<ChatMessage> history = List.of(UserMessage.from("previous question"), AiMessage.from("previous answer"));
        given(attributesServiceMock.find(eq(tenantId), eq(stateAssetId), eq(AttributeScope.SERVER_SCOPE), anyList()))
                .willReturn(immediateFuture(List.of(
                        attr(AgentStateStore.SESSION_ID_KEY, UUID.randomUUID().toString()),
                        attr(AgentStateStore.STARTED_TS_KEY, now),
                        attr(AgentStateStore.LAST_ACTIVITY_TS_KEY, now),
                        attr(AgentStateStore.HISTORY_KEY,
                                dev.langchain4j.data.message.ChatMessageSerializer.messagesToJson(history)))));
        givenChatResponses(textResponse("done"));
        node.init(ctxMock, configuration());

        node.onMsg(ctxMock, msg());

        var requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        then(aiChatModelServiceMock).should().sendChatRequestAsync(any(), requestCaptor.capture());
        assertThat(requestCaptor.getValue().messages()).hasSize(4); // system + 2 replayed + new user message
    }

    @Test
    void givenAnyRun_whenOnMsg_thenDebugEventsArePersistedRegardlessOfNodeDebugSettings() throws Exception {
        config.setEnabledTools(Set.of());
        givenChatResponses(textResponse("done"));
        node.init(ctxMock, configuration());

        node.onMsg(ctxMock, msg());

        then(eventServiceMock).should(atLeastOnce()).saveAsync(any());
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private TbNodeConfiguration configuration() {
        return new TbNodeConfiguration(JacksonUtil.valueToTree(config));
    }

    private TbMsg msg() {
        return TbMsg.newMsg()
                .originator(deviceId)
                .data(TbMsg.EMPTY_JSON_OBJECT)
                .metaData(TbMsgMetaData.EMPTY)
                .build();
    }

    private void givenNoStoredState() {
        lenient().when(attributesServiceMock.find(eq(tenantId), eq(stateAssetId), eq(AttributeScope.SERVER_SCOPE), anyList()))
                .thenReturn(immediateFuture(List.of()));
    }

    private void givenChatResponses(ChatResponse first, ChatResponse... rest) {
        var stub = given(aiChatModelServiceMock.sendChatRequestAsync(any(), any()))
                .willReturn(FluentFuture.from(immediateFuture(first)));
        for (ChatResponse response : rest) {
            stub = stub.willReturn(FluentFuture.from(immediateFuture(response)));
        }
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    private static ChatResponse toolCallResponse(String toolName, String arguments) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                        .id(UUID.randomUUID().toString())
                        .name(toolName)
                        .arguments(arguments)
                        .build()))
                .build();
    }

    private static AttributeKvEntry attr(String key, String value) {
        return new BaseAttributeKvEntry(new StringDataEntry(key, value), System.currentTimeMillis());
    }

    private static AttributeKvEntry attr(String key, long value) {
        return new BaseAttributeKvEntry(new LongDataEntry(key, value), System.currentTimeMillis());
    }

}
