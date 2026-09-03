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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thingsboard.common.util.DirectListeningExecutor;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.rule.engine.api.AttributesSaveRequest;
import org.thingsboard.rule.engine.api.RuleEngineTelemetryService;
import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.common.data.id.AssetId;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.common.data.kv.BaseAttributeKvEntry;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.DoubleDataEntry;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.dao.attributes.AttributesService;
import org.thingsboard.server.dao.timeseries.TimeseriesService;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.google.common.util.concurrent.Futures.immediateFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class AgentToolsTest {

    @Mock
    TbContext ctxMock;
    @Mock
    AttributesService attributesServiceMock;
    @Mock
    TimeseriesService timeseriesServiceMock;
    @Mock
    RuleEngineTelemetryService telemetryServiceMock;

    TenantId tenantId = TenantId.fromUUID(UUID.randomUUID());
    DeviceId originator = new DeviceId(UUID.randomUUID());
    DeviceId otherDevice = new DeviceId(UUID.randomUUID());
    AssetId stateAssetId = new AssetId(UUID.randomUUID());

    AgentStateStore store;
    AgentToolContext toolCtx;

    @BeforeEach
    void setup() {
        store = new AgentStateStore();
        lenient().when(ctxMock.getTenantId()).thenReturn(tenantId);
        lenient().when(ctxMock.getAttributesService()).thenReturn(attributesServiceMock);
        lenient().when(ctxMock.getTimeseriesService()).thenReturn(timeseriesServiceMock);
        lenient().when(ctxMock.getTelemetryService()).thenReturn(telemetryServiceMock);
        lenient().when(ctxMock.getDbCallbackExecutor()).thenReturn(DirectListeningExecutor.INSTANCE);
        toolCtx = new AgentToolContext(ctxMock, null, originator, stateAssetId, true);
    }

    // ------------------------------------------------------------------------------------------------ registry

    @Test
    void givenSubsetOfTools_whenRegistryBuilt_thenOnlyThoseToolsAreExposed() {
        var registry = new AgentToolRegistry(EnumSet.of(AgentToolType.READ_ATTRIBUTES, AgentToolType.REMEMBER), store);

        assertThat(registry.names()).containsExactly("read_attributes", "remember");
        assertThat(registry.specifications()).hasSize(2);
        assertThat(registry.get("read_latest_telemetry")).isNull();
    }

    @Test
    void givenNoToolsEnabled_whenRegistryBuilt_thenItIsEmpty() {
        assertThat(new AgentToolRegistry(Set.of(), store).isEmpty()).isTrue();
        assertThat(new AgentToolRegistry(null, store).isEmpty()).isTrue();
    }

    @Test
    void givenEveryTool_whenSpecificationBuilt_thenNamesMatchTheOpenAiToolNamePattern() {
        var registry = new AgentToolRegistry(EnumSet.allOf(AgentToolType.class), store);

        assertThat(registry.names()).allMatch(name -> name.matches("[a-zA-Z0-9_-]{1,64}"));
        assertThat(registry.specifications()).hasSize(AgentToolType.values().length);
    }

    // ------------------------------------------------------------------------------------------ data instruments

    @Test
    void givenLatestTelemetryTool_whenExecuted_thenReturnsValuesKeyedByTelemetryKey() throws Exception {
        List<TsKvEntry> latest = List.of(new BasicTsKvEntry(1000L, new DoubleDataEntry("temperature", 21.5)));
        given(timeseriesServiceMock.findLatest(eq(tenantId), eq(originator), anyCollection()))
                .willReturn(immediateFuture(latest));

        String result = new EntityDataTools.ReadLatestTelemetry()
                .execute(toolCtx, JacksonUtil.toJsonNode("{\"keys\":[\"temperature\"]}")).get();

        JsonNode json = JacksonUtil.toJsonNode(result);
        assertThat(json.get("latest").get("temperature").get("value").asDouble()).isEqualTo(21.5);
        assertThat(json.get("latest").get("temperature").get("ts").asLong()).isEqualTo(1000L);
    }

    @Test
    void givenCommaSeparatedKeys_whenExecuted_thenTheyAreStillParsed() throws Exception {
        given(timeseriesServiceMock.findLatest(eq(tenantId), eq(originator), anyCollection()))
                .willReturn(immediateFuture(List.of()));

        new EntityDataTools.ReadLatestTelemetry()
                .execute(toolCtx, JacksonUtil.toJsonNode("{\"keys\":\"temperature, humidity\"}")).get();

        var captor = ArgumentCaptor.forClass(List.class);
        then(timeseriesServiceMock).should().findLatest(eq(tenantId), eq(originator), captor.capture());
        assertThat(captor.getValue()).containsExactly("temperature", "humidity");
    }

    @Test
    void givenRestrictedToOriginator_whenModelPassesAnotherEntityId_thenItIsIgnored() throws Exception {
        given(attributesServiceMock.findAll(tenantId, originator, AttributeScope.SERVER_SCOPE))
                .willReturn(immediateFuture(List.of()));

        String args = "{\"entityId\":\"" + otherDevice.getId() + "\",\"entityType\":\"DEVICE\"}";
        new EntityDataTools.ReadAttributes().execute(toolCtx, JacksonUtil.toJsonNode(args)).get();

        then(attributesServiceMock).should().findAll(tenantId, originator, AttributeScope.SERVER_SCOPE);
        then(attributesServiceMock).should(never()).findAll(tenantId, otherDevice, AttributeScope.SERVER_SCOPE);
    }

    @Test
    void givenUnrestrictedContext_whenModelPassesAnotherEntityId_thenItIsHonoured() throws Exception {
        var unrestricted = new AgentToolContext(ctxMock, null, originator, stateAssetId, false);
        given(attributesServiceMock.findAll(eq(tenantId), eq(otherDevice), eq(AttributeScope.SERVER_SCOPE)))
                .willReturn(immediateFuture(List.of()));

        String args = "{\"entityId\":\"" + otherDevice.getId() + "\",\"entityType\":\"DEVICE\"}";
        new EntityDataTools.ReadAttributes().execute(unrestricted, JacksonUtil.toJsonNode(args)).get();

        then(attributesServiceMock).should().findAll(tenantId, otherDevice, AttributeScope.SERVER_SCOPE);
    }

    @Test
    void givenHistoryToolWithoutKey_whenExecuted_thenReturnsErrorInsteadOfThrowing() throws Exception {
        String result = new EntityDataTools.ReadTelemetryHistory()
                .execute(toolCtx, JacksonUtil.newObjectNode()).get();

        assertThat(JacksonUtil.toJsonNode(result).get("error").asText()).contains("'key'");
        then(timeseriesServiceMock).should(never()).findAll(any(), any(), any());
    }

    // ---------------------------------------------------------------------------------------- memory instruments

    @Test
    void givenRememberTool_whenKeyHasUnsafeCharacters_thenItIsSanitized() throws Exception {
        String result = new MemoryTools.Remember(store)
                .execute(toolCtx, JacksonUtil.toJsonNode("{\"key\":\"last note!/\",\"value\":\"ok\"}")).get();

        assertThat(JacksonUtil.toJsonNode(result).get("key").asText()).isEqualTo("last_note__");
        var captor = ArgumentCaptor.forClass(AttributesSaveRequest.class);
        then(telemetryServiceMock).should().saveAttributes(captor.capture());
        assertThat(captor.getValue().getEntries().get(0).getKey())
                .isEqualTo(AgentStateStore.MEMORY_PREFIX + "last_note__");
        assertThat(captor.getValue().getEntityId()).isEqualTo(stateAssetId);
    }

    @Test
    void givenRememberToolWithoutValue_whenExecuted_thenReturnsErrorAndSavesNothing() throws Exception {
        String result = new MemoryTools.Remember(store)
                .execute(toolCtx, JacksonUtil.toJsonNode("{\"key\":\"a\"}")).get();

        assertThat(JacksonUtil.toJsonNode(result).has("error")).isTrue();
        then(telemetryServiceMock).should(never()).saveAttributes(any());
    }

    @Test
    void givenRecallTool_whenExecuted_thenMemoryKeysAreReturnedWithoutThePrefix() throws Exception {
        List<AttributeKvEntry> stored = List.of(
                new BaseAttributeKvEntry(new StringDataEntry(AgentStateStore.MEMORY_PREFIX + "baseline", "21.5"), 1L),
                new BaseAttributeKvEntry(new StringDataEntry("unrelated", "x"), 1L));
        given(attributesServiceMock.findAll(tenantId, stateAssetId, AttributeScope.SERVER_SCOPE))
                .willReturn(immediateFuture(stored));

        String result = new MemoryTools.Recall(store).execute(toolCtx, JacksonUtil.newObjectNode()).get();

        JsonNode memory = JacksonUtil.toJsonNode(result).get("memory");
        assertThat(memory.get("baseline").asText()).isEqualTo("21.5");
        assertThat(memory.has("unrelated")).isFalse();
    }

}
