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

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.thingsboard.server.common.data.TbTransportService;
import org.thingsboard.server.queue.discovery.TbServiceInfoProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Guards the wiring the feature depends on. Eight of the nine ThingsBoard Java services have no
 * {@link TbTransportService} bean at all, one (a transport) has one, and an operator may enable the
 * heartbeat without supplying a URL. The context has to come up in all three shapes: a monitoring
 * component must never be the reason a service fails to start.
 */
public class OpenSearchHeartbeatWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(TbServiceInfoProvider.class, () -> mock(TbServiceInfoProvider.class))
            .withUserConfiguration(OpenSearchConfiguration.class,
                    OpenSearchHeartbeatConfig.class,
                    OpenSearchHeartbeatService.class);

    @Test
    public void givenHeartbeatNotEnabled_whenContextStarts_thenHeartbeatIsNotRegistered() {
        contextRunner.run(context -> assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(OpenSearchHeartbeatService.class)
                .doesNotHaveBean(OpenSearchClient.class));
    }

    @Test
    public void givenNoTransportBeans_whenContextStarts_thenHeartbeatIsWired() {
        contextRunner
                .withPropertyValues(
                        "heartbeat.opensearch.enabled=true",
                        "heartbeat.opensearch.url=http://localhost:9200")
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .hasSingleBean(OpenSearchHeartbeatService.class)
                            .hasSingleBean(OpenSearchClient.class)
                            .hasSingleBean(OpenSearchRetryPolicy.class);
                    assertThat(context.getBean(OpenSearchHeartbeatService.class).isDisabled()).isFalse();
                });
    }

    @Test
    public void givenTransportBeanPresent_whenContextStarts_thenHeartbeatIsWired() {
        TbTransportService mqtt = mock(TbTransportService.class);
        contextRunner
                .withBean("mqttTransportService", TbTransportService.class, () -> mqtt)
                .withPropertyValues(
                        "heartbeat.opensearch.enabled=true",
                        "heartbeat.opensearch.url=http://localhost:9200")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(OpenSearchHeartbeatService.class));
    }

    @Test
    public void givenEnabledWithoutUrl_whenContextStarts_thenContextStillStartsWithHeartbeatDisabled() {
        contextRunner
                .withPropertyValues("heartbeat.opensearch.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(OpenSearchHeartbeatService.class).isDisabled()).isTrue();
                });
    }

    @Test
    public void givenRetryProperties_whenContextStarts_thenPolicyIsBoundFromConfiguration() {
        contextRunner
                .withPropertyValues(
                        "heartbeat.opensearch.enabled=true",
                        "heartbeat.opensearch.url=http://localhost:9200",
                        "heartbeat.opensearch.retry.max_attempts=4",
                        "heartbeat.opensearch.retry.initial_backoff_ms=250",
                        "heartbeat.opensearch.retry.backoff_multiplier=3.0",
                        "heartbeat.opensearch.retry.max_backoff_ms=4000",
                        "heartbeat.opensearch.retry.jitter=0.1")
                .run(context -> {
                    OpenSearchRetryPolicy policy = context.getBean(OpenSearchRetryPolicy.class);
                    assertThat(policy.getMaxAttempts()).isEqualTo(4);
                    assertThat(policy.getInitialBackoffMs()).isEqualTo(250);
                    assertThat(policy.getBackoffMultiplier()).isEqualTo(3.0);
                    assertThat(policy.getMaxBackoffMs()).isEqualTo(4000);
                    assertThat(policy.getJitter()).isEqualTo(0.1);
                    assertThat(policy.baseBackoffMs(2)).isEqualTo(750);
                });
    }

    @Test
    public void givenRetryBudgetOverrunningTheInterval_whenContextStarts_thenBudgetIsClampedBelowIt() {
        contextRunner
                .withPropertyValues(
                        "heartbeat.opensearch.enabled=true",
                        "heartbeat.opensearch.url=http://localhost:9200",
                        "heartbeat.opensearch.interval_ms=10000",
                        "heartbeat.opensearch.request_timeout_ms=5000",
                        "heartbeat.opensearch.retry.budget_ms=60000")
                .run(context -> {
                    // A retry chain must not outlive its own tick, or the in-flight guard would drop the next
                    // heartbeat and a struggling deployment would report less often, not more.
                    assertThat(context.getBean(OpenSearchRetryPolicy.class).getRetryBudgetMs()).isEqualTo(5000);
                });
    }

}
