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

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.TbTransportService;
import org.thingsboard.server.gen.transport.TransportProtos.ServiceInfo;
import org.thingsboard.server.queue.discovery.TbServiceInfoProvider;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class OpenSearchHeartbeatServiceTest {

    private record RecordedRequest(String method, String path, String authorization, String body) {
    }

    private final List<RecordedRequest> received = Collections.synchronizedList(new ArrayList<>());

    private HttpServer server;
    private volatile int responseCode = 201;
    private volatile CountDownLatch handlerGate;
    /**
     * Number of leading attempts the stub answers with 503. Letting the stub decide keeps retry tests
     * deterministic; flipping {@link #responseCode} from the test thread races the retry chain.
     */
    private final AtomicInteger failFirstAttempts = new AtomicInteger(0);

    private OpenSearchHeartbeatConfig config;
    private TbServiceInfoProvider serviceInfoProvider;
    private OpenSearchHeartbeatService heartbeatService;
    private OpenSearchClient client;

    private String url;
    private String username;
    private String password;
    private OpenSearchRetryPolicy retryPolicy;

    @BeforeEach
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            CountDownLatch gate = handlerGate;
            if (gate != null) {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = exchange.getRequestBody().readAllBytes();
            received.add(new RecordedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(body, UTF_8)));
            boolean forcedFailure = failFirstAttempts.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0;
            exchange.sendResponseHeaders(forcedFailure ? 503 : responseCode, -1);
            exchange.close();
        });
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();

        url = "http://localhost:" + server.getAddress().getPort();
        username = null;
        password = null;
        // Retrying is off for the default fixture, so each test observes exactly the pushes it triggers.
        retryPolicy = OpenSearchRetryPolicy.builder().maxAttempts(1).build();

        config = new OpenSearchHeartbeatConfig();
        config.setEnabled(true);
        config.setIndex("tb-heartbeat");
        config.setIndexDatePattern("yyyy.MM.dd");
        config.setIntervalMs(30000);
        config.setAppVersion("4.4.0");

        serviceInfoProvider = mock(TbServiceInfoProvider.class);
        when(serviceInfoProvider.getServiceId()).thenReturn("tb-core-0");
        when(serviceInfoProvider.getServiceType()).thenReturn("tb-core");
        when(serviceInfoProvider.isReady()).thenReturn(true);
        when(serviceInfoProvider.getAssignedTenantProfiles()).thenReturn(Set.of());
        when(serviceInfoProvider.getServiceInfo()).thenReturn(ServiceInfo.newBuilder()
                .setServiceId("tb-core-0")
                .addAllServiceTypes(List.of("TB_CORE"))
                .build());
    }

    @AfterEach
    public void tearDown() {
        if (handlerGate != null) {
            handlerGate.countDown();
        }
        if (client != null) {
            client.close();
        }
        server.stop(0);
    }

    @Test
    public void givenEnabledHeartbeat_whenSendHeartbeat_thenPostsToDatedIndexEndpoint() {
        heartbeatService = newService();

        heartbeatService.sendHeartbeat();

        RecordedRequest request = awaitSingleRequest();
        String today = DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneOffset.UTC).format(Instant.now());
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/tb-heartbeat-" + today + "/_doc");
    }

    @Test
    public void givenBlankIndexDatePattern_whenSendHeartbeat_thenPostsToPlainIndex() {
        config.setIndexDatePattern("");
        heartbeatService = newService();

        heartbeatService.sendHeartbeat();

        assertThat(awaitSingleRequest().path()).isEqualTo("/tb-heartbeat/_doc");
    }

    @Test
    public void givenEnabledHeartbeat_whenSendHeartbeat_thenDocumentCarriesServiceIdentity() {
        heartbeatService = newService();

        heartbeatService.sendHeartbeat();

        JsonNode doc = JacksonUtil.toJsonNode(awaitSingleRequest().body());
        assertThat(doc.get("event.dataset").asText()).isEqualTo("thingsboard.heartbeat");
        assertThat(doc.get("service.id").asText()).isEqualTo("tb-core-0");
        assertThat(doc.get("service.type").asText()).isEqualTo("tb-core");
        assertThat(doc.get("service.version").asText()).isEqualTo("4.4.0");
        assertThat(doc.get("service.ready").asBoolean()).isTrue();
        assertThat(doc.get("host.name").asText()).isNotBlank();
        assertThat(Instant.parse(doc.get("@timestamp").asText())).isNotNull();
    }

    @Test
    public void givenMonolithServingEveryRole_whenSendHeartbeat_thenDocumentListsEveryServedType() {
        when(serviceInfoProvider.getServiceType()).thenReturn("monolith");
        when(serviceInfoProvider.getServiceInfo()).thenReturn(ServiceInfo.newBuilder()
                .setServiceId("tb-monolith-0")
                .addAllServiceTypes(List.of("TB_CORE", "TB_RULE_ENGINE", "TB_TRANSPORT", "EDQS"))
                .build());
        heartbeatService = newService();

        heartbeatService.sendHeartbeat();

        JsonNode doc = JacksonUtil.toJsonNode(awaitSingleRequest().body());
        assertThat(doc.get("service.type").asText()).isEqualTo("monolith");
        assertThat(doc.get("service.types")).hasSize(4);
        assertThat(doc.get("service.types").toString())
                .contains("TB_CORE", "TB_RULE_ENGINE", "TB_TRANSPORT", "EDQS");
    }

    @Test
    public void givenTaskProcessors_whenSendHeartbeat_thenDocumentListsTaskTypes() {
        when(serviceInfoProvider.getServiceInfo()).thenReturn(ServiceInfo.newBuilder()
                .setServiceId("tb-core-0")
                .addAllServiceTypes(List.of("TB_CORE"))
                .addAllTaskTypes(List.of("CF_REPROCESSING"))
                .build());
        heartbeatService = newService();

        heartbeatService.sendHeartbeat();

        JsonNode doc = JacksonUtil.toJsonNode(awaitSingleRequest().body());
        assertThat(doc.get("service.task_types").get(0).asText()).isEqualTo("CF_REPROCESSING");
    }

    @Test
    public void givenIsolatedRuleEngine_whenSendHeartbeat_thenDocumentListsAssignedTenantProfiles() {
        UUID profileId = UUID.randomUUID();
        when(serviceInfoProvider.getAssignedTenantProfiles()).thenReturn(Set.of(profileId));
        heartbeatService = newService();

        heartbeatService.sendHeartbeat();

        JsonNode doc = JacksonUtil.toJsonNode(awaitSingleRequest().body());
        assertThat(doc.get("service.assigned_tenant_profiles").get(0).asText()).isEqualTo(profileId.toString());
    }

    @Test
    public void givenNoAssignedProfilesOrTasks_whenSendHeartbeat_thenOmitsThoseFields() {
        heartbeatService = newService();

        heartbeatService.sendHeartbeat();

        JsonNode doc = JacksonUtil.toJsonNode(awaitSingleRequest().body());
        assertThat(doc.has("service.assigned_tenant_profiles")).isFalse();
        assertThat(doc.has("service.task_types")).isFalse();
        assertThat(doc.has("transports")).isFalse();
    }

    @Test
    public void givenServiceNotReady_whenSendHeartbeat_thenDocumentReportsNotReady() {
        when(serviceInfoProvider.isReady()).thenReturn(false);
        heartbeatService = newService();

        heartbeatService.sendHeartbeat();

        JsonNode doc = JacksonUtil.toJsonNode(awaitSingleRequest().body());
        assertThat(doc.get("service.ready").asBoolean()).isFalse();
    }

    @Test
    public void givenEnabledHeartbeat_whenSendHeartbeat_thenDocumentCarriesJvmUptimeAndStartTime() {
        heartbeatService = newService();

        heartbeatService.sendHeartbeat();

        JsonNode doc = JacksonUtil.toJsonNode(awaitSingleRequest().body());
        Instant startTime = Instant.parse(doc.get("process.start_time").asText());
        long uptimeMs = doc.get("process.uptime.ms").asLong();
        assertThat(uptimeMs).isPositive();
        assertThat(startTime).isBefore(Instant.now());
        // The two must describe the same process: start time plus uptime lands at roughly now.
        assertThat(startTime.plusMillis(uptimeMs)).isCloseTo(Instant.now(), within(10, ChronoUnit.SECONDS));
    }

    @Test
    public void givenEnabledHeartbeat_whenSendHeartbeat_thenDocumentCarriesSystemMetrics() {
        heartbeatService = newService();

        heartbeatService.sendHeartbeat();

        JsonNode doc = JacksonUtil.toJsonNode(awaitSingleRequest().body());
        assertThat(doc.get("system.cpu.count").asInt()).isPositive();
    }

    @Test
    public void givenTransportServices_whenSendHeartbeat_thenDocumentListsTransportNames() {
        TbTransportService mqtt = mock(TbTransportService.class);
        when(mqtt.getName()).thenReturn("MQTT");
        heartbeatService = newService(List.of(mqtt));

        heartbeatService.sendHeartbeat();

        JsonNode doc = JacksonUtil.toJsonNode(awaitSingleRequest().body());
        assertThat(doc.get("transports")).hasSize(1);
        assertThat(doc.get("transports").get(0).asText()).isEqualTo("MQTT");
    }

    @Test
    public void givenConfiguredLabels_whenSendHeartbeat_thenDocumentCarriesLabels() {
        config.setLabels("env=prod,cluster=eu-1");
        heartbeatService = newService();

        heartbeatService.sendHeartbeat();

        JsonNode doc = JacksonUtil.toJsonNode(awaitSingleRequest().body());
        assertThat(doc.get("labels").get("env").asText()).isEqualTo("prod");
        assertThat(doc.get("labels").get("cluster").asText()).isEqualTo("eu-1");
    }

    @Test
    public void givenCredentials_whenSendHeartbeat_thenSendsBasicAuthHeader() {
        username = "admin";
        password = "secret";
        heartbeatService = newService();

        heartbeatService.sendHeartbeat();

        String expected = "Basic " + Base64.getEncoder().encodeToString("admin:secret".getBytes(UTF_8));
        assertThat(awaitSingleRequest().authorization()).isEqualTo(expected);
    }

    @Test
    public void givenNoCredentials_whenSendHeartbeat_thenOmitsAuthorizationHeader() {
        heartbeatService = newService();

        heartbeatService.sendHeartbeat();

        assertThat(awaitSingleRequest().authorization()).isNull();
    }

    @Test
    public void givenSuccessiveTicks_whenSendHeartbeat_thenSequenceIncrements() {
        heartbeatService = newService();

        heartbeatService.sendHeartbeat();
        awaitRequestCount(1);
        heartbeatService.sendHeartbeat();
        awaitRequestCount(2);

        assertThat(sequenceOf(0)).isEqualTo(1);
        assertThat(sequenceOf(1)).isEqualTo(2);
    }

    @Test
    public void givenOpenSearchRejectsWrite_whenSendHeartbeat_thenDoesNotThrowAndCountsFailure() {
        responseCode = 503;
        heartbeatService = newService();

        assertThatCode(() -> heartbeatService.sendHeartbeat()).doesNotThrowAnyException();

        awaitRequestCount(1);
        awaitConsecutiveFailures(1);
    }

    @Test
    public void givenOpenSearchUnreachable_whenSendHeartbeat_thenDoesNotThrowAndCountsFailure() {
        url = "http://localhost:1";
        heartbeatService = newService();

        assertThatCode(() -> heartbeatService.sendHeartbeat()).doesNotThrowAnyException();

        awaitConsecutiveFailures(1);
    }

    @Test
    public void givenFailedTick_whenNextTickSucceeds_thenResetsFailureCount() {
        responseCode = 503;
        heartbeatService = newService();

        heartbeatService.sendHeartbeat();
        awaitConsecutiveFailures(1);
        responseCode = 201;
        heartbeatService.sendHeartbeat();

        awaitRequestCount(2);
        awaitConsecutiveFailures(0);
    }

    @Test
    public void givenRetryPolicy_whenFirstAttemptIsOverloaded_thenRetriesUntilAccepted() {
        retryPolicy = fastRetryPolicy(3);
        failFirstAttempts.set(1);
        heartbeatService = newService();

        heartbeatService.sendHeartbeat();

        awaitRequestCount(2);
        // The retry succeeded, so the tick as a whole never counts as a failure.
        awaitConsecutiveFailures(0);
        assertThat(received).hasSize(2);
    }

    @Test
    public void givenRetryPolicy_whenAllAttemptsFail_thenStopsAtMaxAttempts() {
        retryPolicy = fastRetryPolicy(3);
        responseCode = 503;
        heartbeatService = newService();

        heartbeatService.sendHeartbeat();

        awaitConsecutiveFailures(1);
        assertThat(received).hasSize(3);
    }

    @Test
    public void givenRetryPolicy_whenWriteIsRejectedAsBadRequest_thenDoesNotRetry() {
        retryPolicy = fastRetryPolicy(3);
        responseCode = 400;
        heartbeatService = newService();

        heartbeatService.sendHeartbeat();

        awaitConsecutiveFailures(1);
        await().pollDelay(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5))
                .until(() -> received.size() == 1);
    }

    @Test
    public void givenRetriesInProgress_whenNextTickFires_thenSkipsUntilRetriesFinish() {
        retryPolicy = fastRetryPolicy(3);
        responseCode = 503;
        heartbeatService = newService();

        heartbeatService.sendHeartbeat();
        heartbeatService.sendHeartbeat();

        awaitConsecutiveFailures(1);
        // Only the first tick's own attempt chain should have run; the second tick was skipped.
        assertThat(received).hasSize(3);
    }

    @Test
    public void givenBlankUrl_whenInit_thenSelfDisablesWithoutBreakingStartup() {
        url = "  ";

        heartbeatService = newService();

        assertThat(heartbeatService.isDisabled()).isTrue();
        assertThatCode(() -> heartbeatService.sendHeartbeat()).doesNotThrowAnyException();
        assertThat(received).isEmpty();
    }

    @Test
    public void givenPreviousSendStillInFlight_whenSendHeartbeat_thenSkipsTick() {
        handlerGate = new CountDownLatch(1);
        heartbeatService = newService();

        heartbeatService.sendHeartbeat();
        heartbeatService.sendHeartbeat();
        handlerGate.countDown();

        awaitRequestCount(1);
        await().pollDelay(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5))
                .until(() -> received.size() == 1);
    }

    private static OpenSearchRetryPolicy fastRetryPolicy(int maxAttempts) {
        return OpenSearchRetryPolicy.builder()
                .maxAttempts(maxAttempts)
                .initialBackoffMs(20)
                .backoffMultiplier(2.0)
                .maxBackoffMs(100)
                .jitter(0)
                .retryBudgetMs(10000)
                .build();
    }

    private OpenSearchHeartbeatService newService() {
        return newService(List.of());
    }

    private OpenSearchHeartbeatService newService(List<TbTransportService> transportServices) {
        client = new OpenSearchClient(url, username, password, 2000, 3000, retryPolicy);
        OpenSearchHeartbeatService service =
                new OpenSearchHeartbeatService(config, client, serviceInfoProvider, transportServices);
        service.init();
        return service;
    }

    private long sequenceOf(int requestIndex) {
        return JacksonUtil.toJsonNode(received.get(requestIndex).body()).get("sequence").asLong();
    }

    private RecordedRequest awaitSingleRequest() {
        awaitRequestCount(1);
        return received.get(0);
    }

    private void awaitRequestCount(int count) {
        await().atMost(10, TimeUnit.SECONDS).until(() -> received.size() >= count);
    }

    private void awaitConsecutiveFailures(long count) {
        await().atMost(10, TimeUnit.SECONDS).until(() -> heartbeatService.getConsecutiveFailures() == count);
    }

}
