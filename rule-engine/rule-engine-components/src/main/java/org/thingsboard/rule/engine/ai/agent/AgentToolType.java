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

/**
 * Set of instruments the agent may be allowed to call during the loop.
 * Kept as an enum (instead of free-form strings) so that the rule node UI can render checkboxes
 * and so that a tenant can not accidentally expose an instrument that was not reviewed.
 */
public enum AgentToolType {

    /** Read attributes of an entity (SERVER_SCOPE / SHARED_SCOPE / CLIENT_SCOPE). */
    READ_ATTRIBUTES,

    /** Read latest values of the given telemetry keys. */
    READ_LATEST_TELEMETRY,

    /** Read a time window of telemetry history for a single key. */
    READ_TELEMETRY_HISTORY,

    /** List entities related to the message originator. */
    LIST_RELATED_ENTITIES,

    /** Read long-term memory entries previously saved by the agent. */
    RECALL_MEMORY,

    /** Write a long-term memory entry that survives across rule engine messages and sessions. */
    REMEMBER

}
