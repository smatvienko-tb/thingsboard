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
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.TbTransportService;
import org.thingsboard.server.gen.transport.TransportProtos.ServiceInfo;
import org.thingsboard.server.queue.discovery.TbServiceInfoProvider;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.thingsboard.common.util.SystemUtil.getCpuCount;
import static org.thingsboard.common.util.SystemUtil.getCpuUsage;
import static org.thingsboard.common.util.SystemUtil.getDiscSpaceUsage;
import static org.thingsboard.common.util.SystemUtil.getMemoryUsage;
import static org.thingsboard.common.util.SystemUtil.getTotalDiscSpace;
import static org.thingsboard.common.util.SystemUtil.getTotalMemory;

/**
 * Actively pushes a liveness document to OpenSearch on a fixed schedule, so that an external system can tell
 * that this instance is alive without scraping it. Deliberately independent of the actuator/Micrometer endpoint
 * Prometheus pulls from: a push path stays informative precisely when the pull path is the broken thing.
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

    private final OpenSearchHeartbeatConfig config;
    private final OpenSearchClient client;
    private final TbServiceInfoProvider serviceInfoProvider;
    private final List<TbTransportService> transportServices;

    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong consecutiveFailures = new AtomicLong();

    private DateTimeFormatter indexDateFormatter;
    private String serviceVersion;
    private String hostName;
    private String processStartTime;
    private List<String> transportNames;
    private Map<String, String> labels;

    public OpenSearchHeartbeatService(OpenSearchHeartbeatConfig config,
                                      OpenSearchClient client,
                                      TbServiceInfoProvider serviceInfoProvider,
                                      @Nullable List<TbTransportService> transportServices) {
        this.config = config;
        this.client = client;
        this.serviceInfoProvider = serviceInfoProvider;
        this.transportServices = transportServices == null ? List.of() : transportServices;
    }

    @PostConstruct
    public void init() {
        indexDateFormatter = OpenSearchClient.dateFormatter(config.getIndexDatePattern());
        serviceVersion = resolveServiceVersion();
        hostName = resolveHostName();
        processStartTime = DateTimeFormatter.ISO_INSTANT.format(
                Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()));
        transportNames = transportServices.stream()
                .map(TbTransportService::getName)
                .sorted()
                .toList();
        labels = parseLabels(config.getLabels());
        if (isDisabled()) {
            return;
        }
        log.info("Pushing service heartbeat to OpenSearch every {} ms (retry policy: {})",
                config.getIntervalMs(), client.getRetryPolicy());
    }

    @Scheduled(fixedDelayString = "${heartbeat.opensearch.interval_ms:30000}")
    public void sendHeartbeat() {
        if (isDisabled()) {
            return;
        }
        // A hung OpenSearch must not let ticks pile up on the scheduler.
        if (!inFlight.compareAndSet(false, true)) {
            log.debug("Skipping heartbeat tick: previous push is still in flight");
            return;
        }
        try {
            // The timestamp is stamped here, at build time, so a retried push still reports the moment this
            // instance was actually alive rather than the moment the write happened to land.
            String document = JacksonUtil.toString(buildHeartbeat());
            String index = OpenSearchClient.datedIndex(config.getIndex(), indexDateFormatter);
            client.index(index, document)
                    .whenComplete((accepted, error) -> {
                        try {
                            if (error == null && Boolean.TRUE.equals(accepted)) {
                                onSuccess();
                            } else {
                                onFailure(error == null ? "write rejected" : error.getMessage());
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

    public long getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    public boolean isDisabled() {
        return client.isDisabled();
    }

    private ServiceHeartbeat buildHeartbeat() {
        ServiceInfo serviceInfo = serviceInfoProvider.getServiceInfo();
        return ServiceHeartbeat.builder()
                .timestamp(DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
                .eventDataset(EVENT_DATASET)
                .serviceId(serviceInfoProvider.getServiceId())
                .serviceType(serviceInfoProvider.getServiceType())
                .serviceTypes(emptyToNull(serviceInfo.getServiceTypesList()))
                .taskTypes(emptyToNull(serviceInfo.getTaskTypesList()))
                .assignedTenantProfiles(assignedTenantProfiles())
                .serviceLabel(StringUtils.isBlank(serviceInfo.getLabel()) ? null : serviceInfo.getLabel())
                .serviceVersion(serviceVersion)
                .serviceReady(serviceInfoProvider.isReady())
                .hostName(hostName)
                .processStartTime(processStartTime)
                .uptimeMs(ManagementFactory.getRuntimeMXBean().getUptime())
                .sequence(sequence.incrementAndGet())
                .cpuUsage(getCpuUsage().orElse(null))
                .cpuCount(getCpuCount().orElse(null))
                .memoryUsage(getMemoryUsage().orElse(null))
                .memoryTotal(getTotalMemory().orElse(null))
                .diskUsage(getDiscSpaceUsage().orElse(null))
                .diskTotal(getTotalDiscSpace().orElse(null))
                .transports(emptyToNull(transportNames))
                .labels(labels)
                .build();
    }

    private List<String> assignedTenantProfiles() {
        var profiles = serviceInfoProvider.getAssignedTenantProfiles();
        if (profiles == null || profiles.isEmpty()) {
            return null;
        }
        return profiles.stream().map(UUID::toString).sorted().toList();
    }

    private static List<String> emptyToNull(List<String> values) {
        return values == null || values.isEmpty() ? null : values;
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

}
