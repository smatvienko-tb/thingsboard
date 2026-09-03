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

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.common.util.NoOpFutureCallback;
import org.thingsboard.rule.engine.api.AttributesSaveRequest;
import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.common.data.kv.BaseAttributeKvEntry;
import org.thingsboard.server.common.data.kv.BooleanDataEntry;
import org.thingsboard.server.common.data.kv.DoubleDataEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.kv.StringDataEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Loads and persists {@link AgentState} as attributes of the configured state entity.
 * <p>
 * Why attributes and not a rule node state: attributes are visible in the UI, exportable, and can be
 * inspected/edited by an operator while the agent is running - which is exactly what you want while
 * debugging a long agentic session.
 */
@Slf4j
public class AgentStateStore {

    public static final String PREFIX = "aiAgent_";
    public static final String SESSION_ID_KEY = PREFIX + "sessionId";
    public static final String STARTED_TS_KEY = PREFIX + "sessionStartedTs";
    public static final String LAST_ACTIVITY_TS_KEY = PREFIX + "lastActivityTs";
    public static final String ITERATIONS_KEY = PREFIX + "totalIterations";
    public static final String RUNS_KEY = PREFIX + "totalRuns";
    public static final String HISTORY_KEY = PREFIX + "history";
    public static final String LAST_ANSWER_KEY = PREFIX + "lastAnswer";

    /** Long-term memory written by the {@code remember} tool. */
    public static final String MEMORY_PREFIX = PREFIX + "memory_";

    private static final List<String> STATE_KEYS = List.of(
            SESSION_ID_KEY, STARTED_TS_KEY, LAST_ACTIVITY_TS_KEY, ITERATIONS_KEY, RUNS_KEY, HISTORY_KEY);

    public ListenableFuture<AgentState> load(TbContext ctx, EntityId stateEntityId, long sessionTtlMillis, long now) {
        return Futures.transform(
                ctx.getAttributesService().find(ctx.getTenantId(), stateEntityId, AttributeScope.SERVER_SCOPE, STATE_KEYS),
                entries -> toState(entries, sessionTtlMillis, now),
                ctx.getDbCallbackExecutor());
    }

    private AgentState toState(List<AttributeKvEntry> entries, long sessionTtlMillis, long now) {
        Map<String, AttributeKvEntry> byKey = new HashMap<>();
        entries.forEach(e -> byKey.put(e.getKey(), e));
        if (!byKey.containsKey(SESSION_ID_KEY)) {
            return AgentState.newSession(now);
        }
        var state = new AgentState();
        try {
            state.setSessionId(UUID.fromString(str(byKey, SESSION_ID_KEY, UUID.randomUUID().toString())));
        } catch (IllegalArgumentException e) {
            state.setSessionId(UUID.randomUUID());
        }
        state.setStartedTs(lng(byKey, STARTED_TS_KEY, now));
        state.setLastActivityTs(lng(byKey, LAST_ACTIVITY_TS_KEY, now));
        state.setTotalIterations((int) lng(byKey, ITERATIONS_KEY, 0));
        state.setTotalRuns((int) lng(byKey, RUNS_KEY, 0));
        String historyJson = str(byKey, HISTORY_KEY, null);
        if (historyJson != null && !historyJson.isBlank()) {
            try {
                state.setHistory(new ArrayList<>(ChatMessageDeserializer.messagesFromJson(historyJson)));
            } catch (Exception e) {
                log.warn("Failed to deserialize agent history, starting a fresh session", e);
                return AgentState.newSession(now);
            }
        }
        if (state.isExpired(now, sessionTtlMillis)) {
            log.debug("Agent session {} expired, starting a fresh one", state.getSessionId());
            return AgentState.newSession(now);
        }
        return state;
    }

    public void save(TbContext ctx, EntityId stateEntityId, AgentState state, String lastAnswer, boolean persistHistory) {
        List<AttributeKvEntry> entries = new ArrayList<>(7);
        long ts = System.currentTimeMillis();
        entries.add(attr(SESSION_ID_KEY, state.getSessionId().toString(), ts));
        entries.add(attr(STARTED_TS_KEY, state.getStartedTs(), ts));
        entries.add(attr(LAST_ACTIVITY_TS_KEY, state.getLastActivityTs(), ts));
        entries.add(attr(ITERATIONS_KEY, (long) state.getTotalIterations(), ts));
        entries.add(attr(RUNS_KEY, (long) state.getTotalRuns(), ts));
        if (lastAnswer != null) {
            entries.add(attr(LAST_ANSWER_KEY, lastAnswer, ts));
        }
        if (persistHistory) {
            entries.add(attr(HISTORY_KEY, ChatMessageSerializer.messagesToJson(state.getHistory()), ts));
        }
        saveAttributes(ctx, stateEntityId, entries);
    }

    public void saveMemory(TbContext ctx, EntityId stateEntityId, String key, Object value) {
        long ts = System.currentTimeMillis();
        AttributeKvEntry entry;
        String fullKey = MEMORY_PREFIX + key;
        if (value instanceof Boolean b) {
            entry = new BaseAttributeKvEntry(new BooleanDataEntry(fullKey, b), ts);
        } else if (value instanceof Long l) {
            entry = new BaseAttributeKvEntry(new LongDataEntry(fullKey, l), ts);
        } else if (value instanceof Number n) {
            entry = new BaseAttributeKvEntry(new DoubleDataEntry(fullKey, n.doubleValue()), ts);
        } else {
            entry = new BaseAttributeKvEntry(new StringDataEntry(fullKey, String.valueOf(value)), ts);
        }
        saveAttributes(ctx, stateEntityId, List.of(entry));
    }

    public ListenableFuture<List<AttributeKvEntry>> loadMemory(TbContext ctx, EntityId stateEntityId) {
        return Futures.transform(
                ctx.getAttributesService().findAll(ctx.getTenantId(), stateEntityId, AttributeScope.SERVER_SCOPE),
                all -> all.stream().filter(e -> e.getKey().startsWith(MEMORY_PREFIX)).toList(),
                ctx.getDbCallbackExecutor());
    }

    private void saveAttributes(TbContext ctx, EntityId stateEntityId, List<AttributeKvEntry> entries) {
        ctx.getTelemetryService().saveAttributes(AttributesSaveRequest.builder()
                .tenantId(ctx.getTenantId())
                .entityId(stateEntityId)
                .scope(AttributeScope.SERVER_SCOPE)
                .entries(entries)
                .callback(NoOpFutureCallback.instance())
                .build());
    }

    private static AttributeKvEntry attr(String key, String value, long ts) {
        return new BaseAttributeKvEntry(new StringDataEntry(key, value), ts);
    }

    private static AttributeKvEntry attr(String key, long value, long ts) {
        return new BaseAttributeKvEntry(new LongDataEntry(key, value), ts);
    }

    private static String str(Map<String, AttributeKvEntry> byKey, String key, String defaultValue) {
        AttributeKvEntry entry = byKey.get(key);
        return entry == null ? defaultValue : entry.getValueAsString();
    }

    private static long lng(Map<String, AttributeKvEntry> byKey, String key, long defaultValue) {
        AttributeKvEntry entry = byKey.get(key);
        if (entry == null) {
            return defaultValue;
        }
        return entry.getLongValue().orElseGet(() -> {
            try {
                return Long.parseLong(entry.getValueAsString());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        });
    }

}
