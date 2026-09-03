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

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Owns the OpenSearch connection and its retry policy, separately from what any individual feature chooses to
 * write. Only instantiated when the heartbeat is enabled.
 * <p>
 * Lives under {@code org.thingsboard.server.queue.discovery} because that is the package every ThingsBoard Java
 * service already component-scans; if these settings are ever shared with a second OpenSearch feature, promoting
 * the property prefix from {@code heartbeat.opensearch.*} to {@code opensearch.*} is the natural next step.
 */
@Configuration
@ConditionalOnProperty(prefix = "heartbeat.opensearch", name = "enabled", havingValue = "true")
@Getter
@Slf4j
public class OpenSearchConfiguration {

    @Value("${heartbeat.opensearch.url:}")
    private String url;

    @Value("${heartbeat.opensearch.username:}")
    private String username;

    @Value("${heartbeat.opensearch.password:}")
    private String password;

    @Value("${heartbeat.opensearch.connect_timeout_ms:3000}")
    private long connectTimeoutMs;

    @Value("${heartbeat.opensearch.request_timeout_ms:5000}")
    private long requestTimeoutMs;

    @Value("${heartbeat.opensearch.retry.max_attempts:3}")
    private int maxAttempts;

    @Value("${heartbeat.opensearch.retry.initial_backoff_ms:500}")
    private long initialBackoffMs;

    @Value("${heartbeat.opensearch.retry.backoff_multiplier:2.0}")
    private double backoffMultiplier;

    @Value("${heartbeat.opensearch.retry.max_backoff_ms:5000}")
    private long maxBackoffMs;

    @Value("${heartbeat.opensearch.retry.jitter:0.2}")
    private double jitter;

    /**
     * Wall-clock budget for the whole retry chain of one document. Clamped below the heartbeat interval so a
     * retry chain cannot outlive the tick that started it.
     */
    @Value("${heartbeat.opensearch.retry.budget_ms:15000}")
    private long retryBudgetMs;

    @Value("${heartbeat.opensearch.interval_ms:30000}")
    private long heartbeatIntervalMs;

    @Bean
    public OpenSearchRetryPolicy openSearchRetryPolicy() {
        long budget = resolveRetryBudgetMs();
        return OpenSearchRetryPolicy.builder()
                .maxAttempts(maxAttempts)
                .initialBackoffMs(initialBackoffMs)
                .backoffMultiplier(backoffMultiplier)
                .maxBackoffMs(maxBackoffMs)
                .jitter(jitter)
                .retryBudgetMs(budget)
                .build();
    }

    @Bean(destroyMethod = "close")
    public OpenSearchClient openSearchClient(OpenSearchRetryPolicy retryPolicy) {
        if (url == null || url.isBlank()) {
            log.error("OpenSearch heartbeat is enabled but 'heartbeat.opensearch.url' is not set. Heartbeat disabled.");
        }
        return new OpenSearchClient(url, username, password, connectTimeoutMs, requestTimeoutMs, retryPolicy);
    }

    /**
     * A retry chain that outlives its own tick would be skipped by the in-flight guard on the next tick, so a
     * struggling deployment would emit fewer heartbeats rather than more. Keep the budget strictly below the
     * interval, leaving room for the final attempt's request timeout.
     */
    private long resolveRetryBudgetMs() {
        long ceiling = Math.max(0, heartbeatIntervalMs - requestTimeoutMs);
        if (retryBudgetMs > ceiling) {
            log.warn("heartbeat.opensearch.retry.budget_ms ({} ms) leaves no room within the {} ms heartbeat " +
                            "interval for a {} ms request timeout; clamping the retry budget to {} ms",
                    retryBudgetMs, heartbeatIntervalMs, requestTimeoutMs, ceiling);
            return ceiling;
        }
        return retryBudgetMs;
    }

}
