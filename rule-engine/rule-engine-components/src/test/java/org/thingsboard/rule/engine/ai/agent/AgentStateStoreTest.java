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

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thingsboard.common.util.DirectListeningExecutor;
import org.thingsboard.rule.engine.api.AttributesSaveRequest;
import org.thingsboard.rule.engine.api.RuleEngineTelemetryService;
import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.common.data.id.AssetId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.common.data.kv.BaseAttributeKvEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.dao.attributes.AttributesService;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static com.google.common.util.concurrent.Futures.immediateFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class AgentStateStoreTest {

    @Mock
    TbContext ctxMock;
    @Mock
    AttributesService attributesServiceMock;
    @Mock
    RuleEngineTelemetryService telemetryServiceMock;

    AgentStateStore store;

    TenantId tenantId = TenantId.fromUUID(UUID.randomUUID());
    AssetId stateAssetId = new AssetId(UUID.randomUUID());

    @BeforeEach
    void setup() {
        store = new AgentStateStore();
        lenient().when(ctxMock.getTenantId()).thenReturn(tenantId);
        lenient().when(ctxMock.getAttributesService()).thenReturn(attributesServiceMock);
        lenient().when(ctxMock.getTelemetryService()).thenReturn(telemetryServiceMock);
        lenient().when(ctxMock.getDbCallbackExecutor()).thenReturn(DirectListeningExecutor.INSTANCE);
    }

    @Test
    void givenNoAttributes_whenLoad_thenStartsFreshSession() throws Exception {
        givenStoredAttributes(List.of());

        AgentState state = load(60_000L);

        assertThat(state.getSessionId()).isNotNull();
        assertThat(state.getHistory()).isEmpty();
        assertThat(state.getTotalRuns()).isZero();
    }

    @Test
    void givenStoredSession_whenLoad_thenHistoryIsRestored() throws Exception {
        long now = System.currentTimeMillis();
        UUID sessionId = UUID.randomUUID();
        String historyJson = ChatMessageSerializer.messagesToJson(
                List.of(UserMessage.from("hello"), AiMessage.from("hi")));
        givenStoredAttributes(List.of(
                str(AgentStateStore.SESSION_ID_KEY, sessionId.toString()),
                lng(AgentStateStore.STARTED_TS_KEY, now),
                lng(AgentStateStore.LAST_ACTIVITY_TS_KEY, now),
                lng(AgentStateStore.RUNS_KEY, 4),
                str(AgentStateStore.HISTORY_KEY, historyJson)));

        AgentState state = load(60_000L);

        assertThat(state.getSessionId()).isEqualTo(sessionId);
        assertThat(state.getTotalRuns()).isEqualTo(4);
        assertThat(state.getHistory()).hasSize(2);
        assertThat(((UserMessage) state.getHistory().get(0)).singleText()).isEqualTo("hello");
    }

    @Test
    void givenStaleSession_whenLoad_thenStartsFreshSession() throws Exception {
        long stale = System.currentTimeMillis() - 10 * 60_000L;
        UUID sessionId = UUID.randomUUID();
        givenStoredAttributes(List.of(
                str(AgentStateStore.SESSION_ID_KEY, sessionId.toString()),
                lng(AgentStateStore.STARTED_TS_KEY, stale),
                lng(AgentStateStore.LAST_ACTIVITY_TS_KEY, stale),
                str(AgentStateStore.HISTORY_KEY,
                        ChatMessageSerializer.messagesToJson(List.of(UserMessage.from("stale"))))));

        AgentState state = load(60_000L);

        assertThat(state.getSessionId()).isNotEqualTo(sessionId);
        assertThat(state.getHistory()).isEmpty();
    }

    @Test
    void givenCorruptedHistory_whenLoad_thenFallsBackToFreshSession() throws Exception {
        givenStoredAttributes(List.of(
                str(AgentStateStore.SESSION_ID_KEY, UUID.randomUUID().toString()),
                str(AgentStateStore.HISTORY_KEY, "not a json array")));

        AgentState state = load(0L);

        assertThat(state.getHistory()).isEmpty();
    }

    @Test
    void givenPersistConversationDisabled_whenSave_thenHistoryIsNotWritten() {
        AgentState state = AgentState.newSession(System.currentTimeMillis());
        state.getHistory().add(UserMessage.from("secret"));

        store.save(ctxMock, stateAssetId, state, "answer", false);

        assertThat(savedKeys()).doesNotContain(AgentStateStore.HISTORY_KEY);
        assertThat(savedKeys()).contains(AgentStateStore.LAST_ANSWER_KEY);
    }

    @Test
    void whenSaveMemory_thenWritesPrefixedServerScopeAttribute() {
        store.saveMemory(ctxMock, stateAssetId, "baseline_temperature", "21.5");

        var captor = ArgumentCaptor.forClass(AttributesSaveRequest.class);
        then(telemetryServiceMock).should().saveAttributes(captor.capture());
        AttributesSaveRequest request = captor.getValue();
        assertThat(request.getScope()).isEqualTo(AttributeScope.SERVER_SCOPE);
        assertThat(request.getEntries().get(0).getKey()).isEqualTo(AgentStateStore.MEMORY_PREFIX + "baseline_temperature");
    }

    @Test
    void whenLoadMemory_thenOnlyMemoryPrefixedEntriesAreReturned() throws Exception {
        given(attributesServiceMock.findAll(tenantId, stateAssetId, AttributeScope.SERVER_SCOPE))
                .willReturn(immediateFuture(List.of(
                        str(AgentStateStore.MEMORY_PREFIX + "a", "1"),
                        str(AgentStateStore.SESSION_ID_KEY, UUID.randomUUID().toString()),
                        str("unrelated", "x"))));

        List<AttributeKvEntry> memory = store.loadMemory(ctxMock, stateAssetId).get();

        assertThat(memory).hasSize(1);
        assertThat(memory.get(0).getKey()).isEqualTo(AgentStateStore.MEMORY_PREFIX + "a");
    }

    // ------------------------------------------------------------------------------------- history trimming

    @Test
    void givenHistoryLongerThanWindow_whenTrim_thenKeepsTail() {
        AgentState state = AgentState.newSession(0L);
        for (int i = 0; i < 10; i++) {
            state.getHistory().add(UserMessage.from("m" + i));
        }

        state.trimTo(4);

        assertThat(state.getHistory()).hasSize(4);
        assertThat(((UserMessage) state.getHistory().get(0)).singleText()).isEqualTo("m6");
    }

    @Test
    void givenWindowCutsThroughToolPair_whenTrim_thenOrphanedToolResultIsDropped() {
        var toolRequest = ToolExecutionRequest.builder().id("1").name("recall").arguments("{}").build();
        AgentState state = AgentState.newSession(0L);
        state.getHistory().add(UserMessage.from("question"));
        state.getHistory().add(AiMessage.from(toolRequest));
        state.getHistory().add(ToolExecutionResultMessage.from(toolRequest, "{}"));
        state.getHistory().add(AiMessage.from("answer"));

        state.trimTo(2);

        // the window would start on the orphaned tool result, so it is skipped
        assertThat(state.getHistory()).hasSize(1);
        assertThat(state.getHistory().get(0)).isInstanceOf(AiMessage.class);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private AgentState load(long ttlMillis) throws ExecutionException, InterruptedException {
        return store.load(ctxMock, stateAssetId, ttlMillis, System.currentTimeMillis()).get();
    }

    private void givenStoredAttributes(List<AttributeKvEntry> entries) {
        given(attributesServiceMock.find(eq(tenantId), eq(stateAssetId), eq(AttributeScope.SERVER_SCOPE), anyList()))
                .willReturn(immediateFuture(entries));
    }

    private List<String> savedKeys() {
        var captor = ArgumentCaptor.forClass(AttributesSaveRequest.class);
        then(telemetryServiceMock).should().saveAttributes(captor.capture());
        return captor.getValue().getEntries().stream().map(AttributeKvEntry::getKey).toList();
    }

    private static AttributeKvEntry str(String key, String value) {
        return new BaseAttributeKvEntry(new StringDataEntry(key, value), System.currentTimeMillis());
    }

    private static AttributeKvEntry lng(String key, long value) {
        return new BaseAttributeKvEntry(new LongDataEntry(key, value), System.currentTimeMillis());
    }

}
