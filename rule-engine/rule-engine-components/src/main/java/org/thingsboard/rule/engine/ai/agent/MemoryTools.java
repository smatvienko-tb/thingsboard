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
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import lombok.RequiredArgsConstructor;
import org.thingsboard.common.util.JacksonUtil;

import static org.thingsboard.rule.engine.ai.agent.EntityDataTools.error;
import static org.thingsboard.rule.engine.ai.agent.EntityDataTools.text;

/**
 * Long-term memory instruments.
 * <p>
 * Memory entries are plain SERVER_SCOPE attributes prefixed with {@link AgentStateStore#MEMORY_PREFIX}
 * on the agent state entity, so an operator can read - and correct - what the agent believes it knows.
 */
public final class MemoryTools {

    private MemoryTools() {}

    /** Keys are used verbatim as attribute suffixes, so keep them tame. */
    private static final int MAX_KEY_LENGTH = 128;
    private static final int MAX_VALUE_LENGTH = 10_000;

    @RequiredArgsConstructor
    public static final class Remember implements AgentTool {

        private final AgentStateStore store;

        @Override
        public AgentToolType type() {
            return AgentToolType.REMEMBER;
        }

        @Override
        public String name() {
            return "remember";
        }

        @Override
        public ToolSpecification specification() {
            return ToolSpecification.builder()
                    .name(name())
                    .description("Saves a fact into long-term memory so it survives after this conversation ends. " +
                            "Use short, stable keys (for example 'baseline_temperature' or 'last_maintenance_note').")
                    .parameters(JsonObjectSchema.builder()
                            .addStringProperty("key", "Memory key, up to " + MAX_KEY_LENGTH + " characters.")
                            .addStringProperty("value", "Value to remember.")
                            .required("key", "value")
                            .build())
                    .build();
        }

        @Override
        public ListenableFuture<String> execute(AgentToolContext toolCtx, JsonNode args) {
            String key = text(args, "key", null);
            String value = text(args, "value", null);
            if (key == null || key.isBlank() || value == null) {
                return Futures.immediateFuture(error("'key' and 'value' arguments are required"));
            }
            String sanitizedKey = key.trim().replaceAll("[^a-zA-Z0-9_.-]", "_");
            if (sanitizedKey.length() > MAX_KEY_LENGTH) {
                sanitizedKey = sanitizedKey.substring(0, MAX_KEY_LENGTH);
            }
            String storedValue = value.length() > MAX_VALUE_LENGTH ? value.substring(0, MAX_VALUE_LENGTH) : value;
            store.saveMemory(toolCtx.ctx(), toolCtx.stateEntityId(), sanitizedKey, storedValue);
            ObjectNode result = JacksonUtil.newObjectNode();
            result.put("saved", true);
            result.put("key", sanitizedKey);
            return Futures.immediateFuture(result.toString());
        }

    }

    @RequiredArgsConstructor
    public static final class Recall implements AgentTool {

        private final AgentStateStore store;

        @Override
        public AgentToolType type() {
            return AgentToolType.RECALL_MEMORY;
        }

        @Override
        public String name() {
            return "recall";
        }

        @Override
        public ToolSpecification specification() {
            return ToolSpecification.builder()
                    .name(name())
                    .description("Returns everything the agent has previously saved into long-term memory. " +
                            "Call it early when you need context from past runs.")
                    .parameters(JsonObjectSchema.builder().build())
                    .build();
        }

        @Override
        public ListenableFuture<String> execute(AgentToolContext toolCtx, JsonNode args) {
            return Futures.transform(
                    store.loadMemory(toolCtx.ctx(), toolCtx.stateEntityId()),
                    entries -> {
                        ObjectNode result = JacksonUtil.newObjectNode();
                        ObjectNode memory = result.putObject("memory");
                        entries.forEach(entry -> {
                            String shortKey = entry.getKey().substring(AgentStateStore.MEMORY_PREFIX.length());
                            memory.put(shortKey, entry.getValueAsString());
                        });
                        return result.toString();
                    },
                    toolCtx.ctx().getDbCallbackExecutor());
        }

    }

}
