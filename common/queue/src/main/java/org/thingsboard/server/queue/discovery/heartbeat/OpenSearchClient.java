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
import org.thingsboard.server.common.data.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Minimal OpenSearch write client built on the JDK HTTP client, with exponential backoff on retryable
 * failures. Deliberately not the official OpenSearch REST client: {@code common/queue} is on the classpath of
 * every ThingsBoard Java service, and this needs no new dependency in any of them.
 * <p>
 * Every write is asynchronous and never completes exceptionally to the caller: a failed write resolves to
 * {@code false}. Callers are monitoring paths, and a monitoring path must not be able to break its host.
 */
@Slf4j
public class OpenSearchClient {

    private static final int MAX_LOGGED_RESPONSE_LENGTH = 256;

    private final String baseUrl;
    private final String authorizationHeader;
    private final Duration requestTimeout;
    private final HttpClient httpClient;

    @Getter
    private final OpenSearchRetryPolicy retryPolicy;

    /**
     * True when no URL was configured. The client then accepts writes and discards them, so that a
     * misconfigured monitoring path cannot stop the service it was meant to observe.
     */
    @Getter
    private final boolean disabled;

    public OpenSearchClient(String url, String username, String password,
                            long connectTimeoutMs, long requestTimeoutMs,
                            OpenSearchRetryPolicy retryPolicy) {
        this.retryPolicy = retryPolicy;
        this.requestTimeout = Duration.ofMillis(requestTimeoutMs);
        if (StringUtils.isBlank(url)) {
            this.disabled = true;
            this.baseUrl = null;
            this.authorizationHeader = null;
            this.httpClient = null;
            return;
        }
        this.disabled = false;
        String trimmed = url.trim();
        this.baseUrl = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        if (StringUtils.isNotBlank(username)) {
            String credentials = username + ":" + (password == null ? "" : password);
            this.authorizationHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(UTF_8));
        } else {
            this.authorizationHeader = null;
        }
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Indexes one document, retrying retryable failures with exponential backoff.
     *
     * @return a future resolving to {@code true} when the document was accepted; never completes exceptionally
     */
    public CompletableFuture<Boolean> index(String index, String documentJson) {
        if (disabled) {
            return CompletableFuture.completedFuture(false);
        }
        URI uri = URI.create(baseUrl + "/" + index + "/_doc");
        return attempt(uri, documentJson, new AtomicInteger(1), System.currentTimeMillis());
    }

    /**
     * Resolves an index name with an optional date suffix, e.g. {@code tb-heartbeat-2026.09.03}.
     */
    public static String datedIndex(String index, DateTimeFormatter dateFormatter) {
        return dateFormatter == null ? index : index + "-" + dateFormatter.format(Instant.now());
    }

    public static DateTimeFormatter dateFormatter(String pattern) {
        return StringUtils.isBlank(pattern) ? null
                : DateTimeFormatter.ofPattern(pattern.trim()).withZone(ZoneOffset.UTC);
    }

    private CompletableFuture<Boolean> attempt(URI uri, String documentJson, AtomicInteger attemptNo, long startedAt) {
        int currentAttempt = attemptNo.get();
        return httpClient.sendAsync(buildRequest(uri, documentJson), HttpResponse.BodyHandlers.ofString())
                .handle((response, error) -> classify(response, error, currentAttempt))
                .thenCompose(outcome -> {
                    if (outcome.accepted()) {
                        if (currentAttempt > 1) {
                            log.info("OpenSearch write to {} succeeded on attempt {}", uri.getPath(), currentAttempt);
                        }
                        return CompletableFuture.completedFuture(true);
                    }
                    long elapsed = System.currentTimeMillis() - startedAt;
                    if (!outcome.retryable() || !retryPolicy.shouldRetry(currentAttempt, elapsed)) {
                        log.debug("Giving up OpenSearch write to {} after attempt {} ({} ms): {}",
                                uri.getPath(), currentAttempt, elapsed, outcome.reason());
                        return CompletableFuture.completedFuture(false);
                    }
                    long backoff = retryPolicy.nextBackoffMs(currentAttempt);
                    log.debug("Retrying OpenSearch write to {} in {} ms (attempt {} failed: {})",
                            uri.getPath(), backoff, currentAttempt, outcome.reason());
                    attemptNo.incrementAndGet();
                    Executor delayed = CompletableFuture.delayedExecutor(backoff, TimeUnit.MILLISECONDS);
                    return CompletableFuture.supplyAsync(() -> attempt(uri, documentJson, attemptNo, startedAt), delayed)
                            .thenCompose(future -> future);
                })
                .exceptionally(error -> {
                    // Belt and braces: the caller is a monitoring path and must never see a failure escape.
                    log.debug("OpenSearch write to {} failed unexpectedly: {}", uri.getPath(), error.getMessage());
                    return false;
                });
    }

    private HttpRequest buildRequest(URI uri, String documentJson) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(documentJson, UTF_8));
        if (authorizationHeader != null) {
            builder.header("Authorization", authorizationHeader);
        }
        return builder.build();
    }

    private Outcome classify(HttpResponse<String> response, Throwable error, int attempt) {
        if (error != null) {
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            return new Outcome(false, OpenSearchRetryPolicy.isRetryable(cause), describe(cause));
        }
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return new Outcome(true, false, null);
        }
        return new Outcome(false, OpenSearchRetryPolicy.isRetryable(status),
                "HTTP " + status + " " + truncate(response.body()));
    }

    private static String describe(Throwable error) {
        return error.getClass().getSimpleName() + ": " + error.getMessage();
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= MAX_LOGGED_RESPONSE_LENGTH ? body
                : body.substring(0, MAX_LOGGED_RESPONSE_LENGTH) + "...";
    }

    public void close() {
        if (httpClient != null) {
            httpClient.shutdownNow();
        }
    }

    private record Outcome(boolean accepted, boolean retryable, String reason) {
    }

}
