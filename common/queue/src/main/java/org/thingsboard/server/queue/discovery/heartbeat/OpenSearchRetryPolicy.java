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

import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff policy for OpenSearch writes.
 * <p>
 * The whole retry sequence is bounded by {@link #retryBudgetMs}, which callers are expected to keep below the
 * heartbeat interval: a retry chain that outlives its own tick would be skipped by the in-flight guard on the
 * next tick, so a service under pressure would emit fewer heartbeats, not more.
 * <p>
 * Jitter is applied to every delay because all services in a cluster tick on the same interval. Without it, a
 * brief OpenSearch outage would synchronise every service onto the same retry schedule and they would then
 * retry in lockstep.
 */
@Getter
@Builder
@Slf4j
public class OpenSearchRetryPolicy {

    /**
     * Total attempts including the first one. 1 disables retrying.
     */
    @Builder.Default
    private final int maxAttempts = 3;

    @Builder.Default
    private final long initialBackoffMs = 500;

    @Builder.Default
    private final double backoffMultiplier = 2.0;

    @Builder.Default
    private final long maxBackoffMs = 5000;

    /**
     * Fraction of each delay randomised, e.g. 0.2 spreads a 1000 ms delay over 800-1200 ms.
     */
    @Builder.Default
    private final double jitter = 0.2;

    /**
     * Wall-clock budget for the whole attempt chain of a single document.
     */
    @Builder.Default
    private final long retryBudgetMs = 15000;

    /**
     * Deterministic delay before the attempt following {@code attempt}, ignoring jitter.
     *
     * @param attempt 1-based number of the attempt that just failed
     */
    public long baseBackoffMs(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1, got " + attempt);
        }
        double delay = initialBackoffMs * Math.pow(backoffMultiplier, attempt - 1);
        return (long) Math.min(delay, maxBackoffMs);
    }

    /**
     * Delay before the attempt following {@code attempt}, with jitter applied.
     */
    public long nextBackoffMs(int attempt) {
        long base = baseBackoffMs(attempt);
        if (jitter <= 0) {
            return base;
        }
        long spread = (long) (base * jitter);
        return base + ThreadLocalRandom.current().nextLong(-spread, spread + 1);
    }

    public boolean shouldRetry(int attempt, long elapsedMs) {
        return attempt < maxAttempts && elapsedMs < retryBudgetMs;
    }

    /**
     * Whether a failed write is worth retrying. Overload and transient transport faults are; a rejected
     * request is not, since retrying a misconfiguration only multiplies the load and the log noise.
     */
    public static boolean isRetryable(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    public static boolean isRetryable(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof IOException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

}
