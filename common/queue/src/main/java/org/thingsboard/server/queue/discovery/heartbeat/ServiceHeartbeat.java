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
package org.thingsboard.server.queue.discovery.heartbeat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * A single liveness document pushed to OpenSearch. Field names follow the Elastic Common Schema
 * so that the index is usable from stock OpenSearch Dashboards without a custom mapping.
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ServiceHeartbeat {

    @JsonProperty("@timestamp")
    private final String timestamp;

    @JsonProperty("event.dataset")
    private final String eventDataset;

    @JsonProperty("service.id")
    private final String serviceId;

    @JsonProperty("service.type")
    private final String serviceType;

    @JsonProperty("service.version")
    private final String serviceVersion;

    @JsonProperty("service.ready")
    private final boolean serviceReady;

    @JsonProperty("host.name")
    private final String hostName;

    /**
     * JVM uptime, so that a consumer can tell a restarted service from a continuously running one.
     */
    @JsonProperty("process.uptime.ms")
    private final long uptimeMs;

    /**
     * Monotonic per-process counter. Gaps identify heartbeats that were never delivered.
     */
    @JsonProperty("sequence")
    private final long sequence;

    @JsonProperty("system.cpu.usage")
    private final Integer cpuUsage;

    @JsonProperty("system.cpu.count")
    private final Integer cpuCount;

    @JsonProperty("system.memory.usage")
    private final Integer memoryUsage;

    @JsonProperty("system.memory.total")
    private final Long memoryTotal;

    @JsonProperty("system.disk.usage")
    private final Integer diskUsage;

    @JsonProperty("system.disk.total")
    private final Long diskTotal;

    /**
     * Transport names served by this process. All transports report {@code service.type: tb-transport},
     * so this is what distinguishes an MQTT process from a CoAP one.
     */
    @JsonProperty("transports")
    private final List<String> transports;

    @JsonProperty("labels")
    private final Map<String, String> labels;

}
