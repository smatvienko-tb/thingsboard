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
import com.google.common.util.concurrent.ListenableFuture;
import dev.langchain4j.agent.tool.ToolSpecification;

/**
 * A single instrument exposed to the LLM.
 * <p>
 * Implementations must be stateless: everything request-scoped comes in via {@link AgentToolContext}.
 */
public interface AgentTool {

    AgentToolType type();

    /** Tool name as seen by the model. Must match {@code [a-zA-Z0-9_-]{1,64}}. */
    String name();

    ToolSpecification specification();

    /**
     * Executes the instrument.
     *
     * @param toolCtx request-scoped context (rule engine ctx, originator, state entity)
     * @param args    arguments produced by the model, already parsed; never {@code null} (may be an empty object)
     * @return future with a JSON string that is fed back to the model as a tool result
     */
    ListenableFuture<String> execute(AgentToolContext toolCtx, JsonNode args);

}
