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

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "heartbeat.opensearch", name = "enabled", havingValue = "true")
@Data
public class OpenSearchHeartbeatConfig {

    @Value("${heartbeat.opensearch.enabled:false}")
    private boolean enabled;

    /**
     * Base OpenSearch URL, e.g. {@code https://opensearch:9200}. Required when enabled.
     */
    @Value("${heartbeat.opensearch.url:}")
    private String url;

    @Value("${heartbeat.opensearch.index:tb-heartbeat}")
    private String index;

    /**
     * Date suffix appended to the index name, so heartbeats roll into daily indices.
     * Blank writes every heartbeat to {@code index} with no suffix.
     */
    @Value("${heartbeat.opensearch.index_date_pattern:yyyy.MM.dd}")
    private String indexDatePattern;

    @Value("${heartbeat.opensearch.username:}")
    private String username;

    @Value("${heartbeat.opensearch.password:}")
    private String password;

    /**
     * Comma-separated {@code key=value} pairs added to every heartbeat, e.g. {@code env=prod,cluster=eu-1}.
     * Lets one OpenSearch cluster serve several ThingsBoard deployments.
     */
    @Value("${heartbeat.opensearch.labels:}")
    private String labels;

    @Value("${app.version:unknown}")
    private String appVersion;

    @Value("${heartbeat.opensearch.interval_ms:30000}")
    private long intervalMs;

    @Value("${heartbeat.opensearch.connect_timeout_ms:3000}")
    private int connectTimeoutMs;

    /**
     * Kept below the heartbeat interval so a stalled push cannot outlive the tick that started it.
     */
    @Value("${heartbeat.opensearch.request_timeout_ms:5000}")
    private int requestTimeoutMs;

}
