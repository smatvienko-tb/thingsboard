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

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.thingsboard.rule.engine.api.NodeConfiguration;
import org.thingsboard.server.common.data.id.AiModelId;
import org.thingsboard.server.common.data.validation.Length;

import java.util.EnumSet;
import java.util.Set;

@Data
public class TbAiAgentNodeConfiguration implements NodeConfiguration<TbAiAgentNodeConfiguration> {

    @NotNull
    private AiModelId modelId;

    @NotBlank
    @Length(min = 1, max = 500_000)
    private String systemPrompt;

    @NotBlank
    @Length(min = 1, max = 500_000)
    private String userPrompt;

    /** Instruments the agent is allowed to call. Empty set turns this node into a plain single-shot AI request. */
    private Set<AgentToolType> enabledTools;

    /** When true, data-reading instruments always operate on the message originator, ignoring model-supplied ids. */
    private boolean restrictToolsToOriginator = true;

    /** Max number of LLM round trips per incoming message. The hard stop for a runaway loop. */
    @Min(value = 1, message = "must be at least 1 iteration")
    @Max(value = 50, message = "cannot exceed 50 iterations")
    private int maxIterations = 10;

    @Min(value = 1, message = "must be at least 1 second")
    @Max(value = 600, message = "cannot exceed 600 seconds (10 minutes)")
    private int timeoutSeconds = 60;

    /**
     * Name of the asset that stores the agent session state and long-term memory.
     * Supports {@code ${metadataKey}} and {@code $[msgKey]} patterns, e.g. {@code "AI Agent ${deviceName}"}.
     */
    @NotBlank
    @Length(min = 1, max = 255)
    private String stateAssetName;

    /** Asset profile used when the state asset has to be created. */
    @Length(min = 1, max = 255)
    private String stateAssetProfile = "AI Agent";

    private boolean createStateAssetIfMissing = true;

    /** Keep the conversation across messages, so a long session survives rule engine restarts. */
    private boolean persistConversation = true;

    /** How many trailing chat messages are carried into the next run. 0 disables trimming. */
    @Min(value = 0, message = "cannot be negative")
    @Max(value = 500, message = "cannot exceed 500 messages")
    private int memoryWindowSize = 40;

    /** Inactivity after which a new session (and a clean conversation) is started. 0 - never expire. */
    @Min(value = 0, message = "cannot be negative")
    private long sessionTtlMinutes = 60;

    private boolean forceAck = true;

    @Override
    public TbAiAgentNodeConfiguration defaultConfiguration() {
        var configuration = new TbAiAgentNodeConfiguration();
        configuration.setSystemPrompt("""
                You are an IoT operations agent running inside the ThingsBoard rule engine.
                You are given a task about a specific device or asset and a set of tools to inspect the platform.

                Rules:
                - Call tools to gather facts. Never invent telemetry values, attribute values or entity names.
                - Prefer the smallest number of tool calls that answers the task.
                - Use 'recall' at the start when past context could matter, and 'remember' to persist a conclusion
                  that will still be useful on the next message.
                - When you have enough information, reply with a final answer in plain text and stop calling tools.
                """);
        configuration.setUserPrompt("Message: $[*]\nMetadata: ${*}");
        configuration.setEnabledTools(EnumSet.of(
                AgentToolType.READ_ATTRIBUTES,
                AgentToolType.READ_LATEST_TELEMETRY,
                AgentToolType.READ_TELEMETRY_HISTORY,
                AgentToolType.RECALL_MEMORY,
                AgentToolType.REMEMBER));
        configuration.setStateAssetName("AI Agent State");
        return configuration;
    }

}
