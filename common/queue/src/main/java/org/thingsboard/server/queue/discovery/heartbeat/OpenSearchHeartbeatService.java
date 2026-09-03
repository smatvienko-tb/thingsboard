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

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.TbTransportService;
import org.thingsboard.server.queue.discovery.TbServiceInfoProvider;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.thingsboard.common.util.SystemUtil.getCpuCount;
import static org.thingsboard.common.util.SystemUtil.getCpuUsage;
import static org.thingsboard.common.util.SystemUtil.getDiscSpaceUsage;
import static org.thingsboard.common.util.SystemUtil.getMemoryUsage;
import static org.thingsboard.common.util.SystemUtil.getTotalDiscSpace;
import static org.thingsboard.common.util.SystemUtil.getTotalMemory;

/**
 * Actively pushes a liveness document to OpenSearch on a fixed schedule, so that an external system can
 * tell that this service is alive without scraping it. This is deliberately independent of the
 * actuator/Micrometer endpoint Prometheus pulls from: a push path stays informative precisely when the
 * pull path is the broken thing.
 * <p>
 * Lives in {@code org.thingsboard.server.queue.discovery} because every ThingsBoard Java service already
 * component-scans that package and enables scheduling, so no per-service wiring is required.
 */
@Component
@ConditionalOnProperty(prefix = "heartbeat.opensearch", name = "enabled", havingValue = "true")
@Slf4j
public class OpenSearchHeartbeatService {

    private static final String EVENT_DATASET = "thingsboard.heartbeat";
    private static final long FAILURE_LOG_INTERVAL = 10;
    private static final int MAX_LOGGED_RESPONSE_LENGTH = 256;

    private final OpenSearchHeartbeatConfig config;
    private final TbServiceInfoProvider serviceInfoProvider;
    private final List<TbTransportService> transportServices;

    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong consecutiveFailures = new AtomicLong();

    private volatile boolean disabled;

    private HttpClient httpClient;
    private String baseUrl;
    private DateTimeFormatter indexDateFormatter;
    private String authorizationHeader;
    private String serviceVersion;
    private String hostName;
    private List<String> transportNames;
    private Map<String, String> labels;

    public OpenSearchHeartbeatService(OpenSearchHeartbeatConfig config,
                                      TbServiceInfoProvider serviceInfoProvider,
                                      @Nullable List<TbTransportService> transportServices) {
        this.config = config;
        this.serviceInfoProvider = serviceInfoProvider;
        this.transportServices = transportServices == null ? List.of() : transportServices;
    }

    @PostConstruct
    public void init() {
        if (StringUtils.isBlank(config.getUrl())) {
            // A misconfigured monitoring path must never stop the service it is meant to observe.
            disabled = true;
            log.error("OpenSearch heartbeat is enabled but 'heartbeat.opensearch.url' is not set. Heartbeat disabled.");
            return;
        }
        String trimmedUrl = config.getUrl().trim();
        baseUrl = trimmedUrl.endsWith("/") ? trimmedUrl.substring(0, trimmedUrl.length() - 1) : trimmedUrl;
        indexDateFormatter = StringUtils.isBlank(config.getIndexDatePattern()) ? null
                : DateTimeFormatter.ofPattern(config.getIndexDatePattern().trim()).withZone(ZoneOffset.UTC);
        if (StringUtils.isNotBlank(config.getUsername())) {
            String password = config.getPassword() == null ? "" : config.getPassword();
            String credentials = config.getUsername() + ":" + password;
            authorizationHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(UTF_8));
        }
        serviceVersion = resolveServiceVersion();
        hostName = resolveHostName();
        transportNames = transportServices.stream()
                .map(TbTransportService::getName)
                .sorted()
                .toList();
        labels = parseLabels(config.getLabels());
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.getConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        log.info("Pushing service heartbeat to OpenSearch at {} every {} ms", baseUrl, config.getIntervalMs());
    }

    @Scheduled(fixedDelayString = "${heartbeat.opensearch.interval_ms:30000}")
    public void sendHeartbeat() {
        if (disabled) {
            return;
        }
        // A hung OpenSearch must not let ticks pile up on the scheduler; the next tick is the retry.
        if (!inFlight.compareAndSet(false, true)) {
            log.debug("Skipping heartbeat tick: previous push is still in flight");
            return;
        }
        try {
            httpClient.sendAsync(buildRequest(), HttpResponse.BodyHandlers.ofString())
                    .whenComplete((response, error) -> {
                        try {
                            if (error != null) {
                                onFailure(error.getMessage());
                            } else if (response.statusCode() >= 200 && response.statusCode() < 300) {
                                onSuccess();
                            } else {
                                onFailure("HTTP " + response.statusCode() + " " + truncate(response.body()));
                            }
                        } finally {
                            inFlight.set(false);
                        }
                    });
        } catch (Throwable t) {
            inFlight.set(false);
            onFailure(t.getMessage());
        }
    }

    @PreDestroy
    public void destroy() {
        if (httpClient != null) {
            httpClient.shutdownNow();
        }
    }

    public long getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    public boolean isDisabled() {
        return disabled;
    }

    private HttpRequest buildRequest() {
        HttpRequest.Builder builder = HttpRequest.newBuilder(resolveEndpoint())
                .timeout(Duration.ofMillis(config.getRequestTimeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JacksonUtil.toString(buildHeartbeat()), UTF_8));
        if (authorizationHeader != null) {
            builder.header("Authorization", authorizationHeader);
        }
        return builder.build();
    }

    private URI resolveEndpoint() {
        String index = config.getIndex();
        if (indexDateFormatter != null) {
            index = index + "-" + indexDateFormatter.format(Instant.now());
        }
        return URI.create(baseUrl + "/" + index + "/_doc");
    }

    private ServiceHeartbeat buildHeartbeat() {
        return ServiceHeartbeat.builder()
                .timestamp(DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
                .eventDataset(EVENT_DATASET)
                .serviceId(serviceInfoProvider.getServiceId())
                .serviceType(serviceInfoProvider.getServiceType())
                .serviceVersion(serviceVersion)
                .serviceReady(serviceInfoProvider.isReady())
                .hostName(hostName)
                .uptimeMs(ManagementFactory.getRuntimeMXBean().getUptime())
                .sequence(sequence.incrementAndGet())
                .cpuUsage(getCpuUsage().orElse(null))
                .cpuCount(getCpuCount().orElse(null))
                .memoryUsage(getMemoryUsage().orElse(null))
                .memoryTotal(getTotalMemory().orElse(null))
                .diskUsage(getDiscSpaceUsage().orElse(null))
                .diskTotal(getTotalDiscSpace().orElse(null))
                .transports(transportNames.isEmpty() ? null : transportNames)
                .labels(labels)
                .build();
    }

    private void onSuccess() {
        long failures = consecutiveFailures.getAndSet(0);
        if (failures > 0) {
            log.info("Heartbeat push to OpenSearch recovered after {} consecutive failure(s)", failures);
        }
    }

    private void onFailure(String reason) {
        long failures = consecutiveFailures.incrementAndGet();
        if (failures == 1 || failures % FAILURE_LOG_INTERVAL == 0) {
            log.warn("Failed to push heartbeat to OpenSearch ({} consecutive failure(s)): {}", failures, reason);
        } else {
            log.debug("Failed to push heartbeat to OpenSearch ({} consecutive failure(s)): {}", failures, reason);
        }
    }

    private String resolveServiceVersion() {
        String version = config.getAppVersion();
        // Guard against an unfiltered '@project.version@' placeholder in services without resource filtering.
        if (StringUtils.isNotBlank(version) && !version.startsWith("@")) {
            return version;
        }
        String fromManifest = getClass().getPackage().getImplementationVersion();
        return StringUtils.isNotBlank(fromManifest) ? fromManifest : "unknown";
    }

    private String resolveHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return serviceInfoProvider.getServiceId();
        }
    }

    private Map<String, String> parseLabels(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String pair : raw.split(",")) {
            if (StringUtils.isBlank(pair)) {
                continue;
            }
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length == 2 && StringUtils.isNotBlank(keyValue[0])) {
                parsed.put(keyValue[0].trim(), keyValue[1].trim());
            } else {
                log.warn("Ignoring malformed heartbeat label '{}', expected 'key=value'", pair);
            }
        }
        return parsed.isEmpty() ? null : parsed;
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= MAX_LOGGED_RESPONSE_LENGTH ? body : body.substring(0, MAX_LOGGED_RESPONSE_LENGTH) + "...";
    }

}
