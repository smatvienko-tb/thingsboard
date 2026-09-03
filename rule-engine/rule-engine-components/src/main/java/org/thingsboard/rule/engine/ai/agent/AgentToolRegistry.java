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

import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the set of instruments enabled for a particular rule node instance and exposes them
 * both as langchain4j {@link ToolSpecification}s (for the request) and by name (for dispatch).
 */
public class AgentToolRegistry {

    private final Map<String, AgentTool> toolsByName = new LinkedHashMap<>();
    private final List<ToolSpecification> specifications = new ArrayList<>();

    public AgentToolRegistry(Set<AgentToolType> enabledTypes, AgentStateStore stateStore) {
        List<AgentTool> all = List.of(
                new EntityDataTools.ReadAttributes(),
                new EntityDataTools.ReadLatestTelemetry(),
                new EntityDataTools.ReadTelemetryHistory(),
                new EntityDataTools.ListRelatedEntities(),
                new MemoryTools.Recall(stateStore),
                new MemoryTools.Remember(stateStore)
        );
        for (AgentTool tool : all) {
            if (enabledTypes != null && enabledTypes.contains(tool.type())) {
                toolsByName.put(tool.name(), tool);
                specifications.add(tool.specification());
            }
        }
    }

    public boolean isEmpty() {
        return toolsByName.isEmpty();
    }

    public List<ToolSpecification> specifications() {
        return specifications;
    }

    public AgentTool get(String name) {
        return toolsByName.get(name);
    }

    public Set<String> names() {
        return toolsByName.keySet();
    }

}
