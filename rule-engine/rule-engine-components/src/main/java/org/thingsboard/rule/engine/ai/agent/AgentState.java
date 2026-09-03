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

import dev.langchain4j.data.message.ChatMessage;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * In-memory representation of a long-running agent session.
 * <p>
 * Persisted as SERVER_SCOPE attributes of the configured state entity by {@link AgentStateStore},
 * so that the whole session is observable from the UI without any additional tooling.
 */
@Data
public class AgentState {

    private UUID sessionId;
    private long startedTs;
    private long lastActivityTs;
    private int totalIterations;
    private int totalRuns;

    /** Conversation so far, excluding the system message (it is re-rendered on every run). */
    private List<ChatMessage> history = new ArrayList<>();

    public static AgentState newSession(long now) {
        var state = new AgentState();
        state.setSessionId(UUID.randomUUID());
        state.setStartedTs(now);
        state.setLastActivityTs(now);
        return state;
    }

    public boolean isExpired(long now, long ttlMillis) {
        return ttlMillis > 0 && now - lastActivityTs > ttlMillis;
    }

    /**
     * Keeps only the last {@code windowSize} messages, but never breaks a tool-call pair:
     * an {@code AiMessage} with tool execution requests must stay together with its results,
     * otherwise most providers reject the next request.
     */
    public void trimTo(int windowSize) {
        if (windowSize <= 0 || history.size() <= windowSize) {
            return;
        }
        int from = history.size() - windowSize;
        // walk forward until we are not standing on an orphaned tool result
        while (from < history.size()
                && history.get(from) instanceof dev.langchain4j.data.message.ToolExecutionResultMessage) {
            from++;
        }
        history = new ArrayList<>(history.subList(from, history.size()));
    }

}
