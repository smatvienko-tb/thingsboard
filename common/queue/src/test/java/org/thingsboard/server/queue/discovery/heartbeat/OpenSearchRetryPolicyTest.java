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

import java.io.IOException;
import java.net.http.HttpConnectTimeoutException;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class OpenSearchRetryPolicyTest {

    private final OpenSearchRetryPolicy policy = OpenSearchRetryPolicy.builder()
            .maxAttempts(5)
            .initialBackoffMs(500)
            .backoffMultiplier(2.0)
            .maxBackoffMs(5000)
            .jitter(0)
            .retryBudgetMs(15000)
            .build();

    @Test
    public void givenExponentialPolicy_whenBaseBackoff_thenDoublesPerAttempt() {
        assertThat(policy.baseBackoffMs(1)).isEqualTo(500);
        assertThat(policy.baseBackoffMs(2)).isEqualTo(1000);
        assertThat(policy.baseBackoffMs(3)).isEqualTo(2000);
        assertThat(policy.baseBackoffMs(4)).isEqualTo(4000);
    }

    @Test
    public void givenGrowingBackoff_whenPastCap_thenClampsToMaxBackoff() {
        assertThat(policy.baseBackoffMs(5)).isEqualTo(5000);
        assertThat(policy.baseBackoffMs(20)).isEqualTo(5000);
    }

    @Test
    public void givenAttemptBelowOne_whenBaseBackoff_thenRejects() {
        assertThatThrownBy(() -> policy.baseBackoffMs(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void givenJitter_whenNextBackoff_thenStaysWithinJitterBand() {
        OpenSearchRetryPolicy jittered = OpenSearchRetryPolicy.builder()
                .initialBackoffMs(1000)
                .backoffMultiplier(2.0)
                .maxBackoffMs(5000)
                .jitter(0.2)
                .build();

        for (int i = 0; i < 200; i++) {
            assertThat(jittered.nextBackoffMs(1)).isBetween(800L, 1200L);
        }
    }

    @Test
    public void givenNoJitter_whenNextBackoff_thenMatchesBaseBackoff() {
        assertThat(policy.nextBackoffMs(2)).isEqualTo(policy.baseBackoffMs(2));
    }

    @Test
    public void givenAttemptsRemaining_whenShouldRetry_thenAllowsUntilMaxAttempts() {
        assertThat(policy.shouldRetry(1, 0)).isTrue();
        assertThat(policy.shouldRetry(4, 0)).isTrue();
        assertThat(policy.shouldRetry(5, 0)).isFalse();
    }

    @Test
    public void givenExhaustedBudget_whenShouldRetry_thenStopsEvenWithAttemptsLeft() {
        assertThat(policy.shouldRetry(1, 15000)).isFalse();
        assertThat(policy.shouldRetry(1, 20000)).isFalse();
    }

    @Test
    public void givenSingleAttemptPolicy_whenShouldRetry_thenNeverRetries() {
        OpenSearchRetryPolicy noRetry = OpenSearchRetryPolicy.builder().maxAttempts(1).build();

        assertThat(noRetry.shouldRetry(1, 0)).isFalse();
    }

    @Test
    public void givenOverloadOrServerError_whenClassified_thenRetryable() {
        assertThat(OpenSearchRetryPolicy.isRetryable(429)).isTrue();
        assertThat(OpenSearchRetryPolicy.isRetryable(500)).isTrue();
        assertThat(OpenSearchRetryPolicy.isRetryable(503)).isTrue();
    }

    @Test
    public void givenRejectedRequest_whenClassified_thenNotRetryable() {
        assertThat(OpenSearchRetryPolicy.isRetryable(400)).isFalse();
        assertThat(OpenSearchRetryPolicy.isRetryable(401)).isFalse();
        assertThat(OpenSearchRetryPolicy.isRetryable(403)).isFalse();
        assertThat(OpenSearchRetryPolicy.isRetryable(404)).isFalse();
    }

    @Test
    public void givenTransportFault_whenClassified_thenRetryable() {
        assertThat(OpenSearchRetryPolicy.isRetryable(new IOException("connection reset"))).isTrue();
        assertThat(OpenSearchRetryPolicy.isRetryable(
                new CompletionException(new HttpConnectTimeoutException("timed out")))).isTrue();
    }

    @Test
    public void givenNonTransportFault_whenClassified_thenNotRetryable() {
        assertThat(OpenSearchRetryPolicy.isRetryable(new IllegalStateException("bug"))).isFalse();
    }

}
