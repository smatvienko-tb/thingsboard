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
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.gen.transport.TransportProtos.ServiceInfo;
import org.thingsboard.server.queue.discovery.TbServiceInfoProvider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the heartbeat against a real OpenSearch, which is the only way to confirm the things a stub HTTP
 * server cannot: that OpenSearch accepts the document shape, that dynamic mapping infers usable types for the
 * dotted ECS field names, and that the document is actually searchable afterwards.
 * <p>
 * Skipped automatically when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@Slf4j
public class OpenSearchHeartbeatIntegrationTest {

    private static final DockerImageName OPENSEARCH_IMAGE =
            DockerImageName.parse("opensearchproject/opensearch:2.19.1");

    private static final String INDEX_PREFIX = "tb-heartbeat-it-";

    @Container
    @SuppressWarnings("resource")
    private static final GenericContainer<?> OPENSEARCH = new GenericContainer<>(OPENSEARCH_IMAGE)
            .withExposedPorts(9200)
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            .waitingFor(Wait.forHttp("/_cluster/health").forPort(9200).forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(3));

    private final HttpClient probe = HttpClient.newHttpClient();

    private OpenSearchClient client;
    private OpenSearchHeartbeatService heartbeatService;

    /**
     * This module runs test methods concurrently (see junit-platform.properties), so every test needs its own
     * index; a shared one would have the tests counting each other's heartbeats.
     */
    private String index;

    private String baseUrl() {
        return "http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200);
    }

    @BeforeEach
    public void setUp() {
        index = INDEX_PREFIX + UUID.randomUUID();

        OpenSearchHeartbeatConfig config = new OpenSearchHeartbeatConfig();
        config.setEnabled(true);
        config.setIndex(index);
        // No date suffix, so the assertions can address one predictable index.
        config.setIndexDatePattern("");
        config.setIntervalMs(30000);
        config.setAppVersion("4.4.0");
        config.setLabels("env=it,cluster=local");

        TbServiceInfoProvider serviceInfoProvider = mock(TbServiceInfoProvider.class);
        when(serviceInfoProvider.getServiceId()).thenReturn("tb-monolith-0");
        when(serviceInfoProvider.getServiceType()).thenReturn("monolith");
        when(serviceInfoProvider.isReady()).thenReturn(true);
        when(serviceInfoProvider.getAssignedTenantProfiles()).thenReturn(Set.of());
        when(serviceInfoProvider.getServiceInfo()).thenReturn(ServiceInfo.newBuilder()
                .setServiceId("tb-monolith-0")
                .addAllServiceTypes(List.of("TB_CORE", "TB_RULE_ENGINE", "TB_TRANSPORT", "EDQS"))
                .build());

        client = new OpenSearchClient(baseUrl(), null, null, 3000, 5000,
                OpenSearchRetryPolicy.builder().maxAttempts(3).initialBackoffMs(200).build());
        heartbeatService = new OpenSearchHeartbeatService(config, client, serviceInfoProvider, List.of());
        heartbeatService.init();
    }

    @AfterEach
    public void tearDown() throws IOException, InterruptedException {
        if (client != null) {
            client.close();
        }
        send("DELETE", "/" + index, null);
    }

    @Test
    public void givenRealOpenSearch_whenHeartbeatPushed_thenDocumentIsIndexedAndSearchable() throws Exception {
        heartbeatService.sendHeartbeat();

        awaitDocumentCount(1);

        JsonNode source = firstDocument();
        assertThat(source.get("service.id").asText()).isEqualTo("tb-monolith-0");
        assertThat(source.get("service.type").asText()).isEqualTo("monolith");
        assertThat(source.get("service.types")).hasSize(4);
        assertThat(source.get("service.ready").asBoolean()).isTrue();
        assertThat(source.get("service.version").asText()).isEqualTo("4.4.0");
        assertThat(source.get("sequence").asLong()).isEqualTo(1);
        assertThat(source.get("process.uptime.ms").asLong()).isPositive();
        assertThat(Instant.parse(source.get("process.start_time").asText())).isBefore(Instant.now());
        assertThat(source.get("labels").get("env").asText()).isEqualTo("it");
    }

    @Test
    public void givenRealOpenSearch_whenSeveralHeartbeatsPushed_thenEachIsAppendedWithItsOwnSequence() throws Exception {
        heartbeatService.sendHeartbeat();
        awaitDocumentCount(1);
        heartbeatService.sendHeartbeat();
        awaitDocumentCount(2);

        // Append-only: the second heartbeat must not overwrite the first, so liveness stays queryable as
        // max(@timestamp) by service.id.keyword and the history is preserved.
        assertThat(documentCount(rawCount())).isEqualTo(2);
    }

    @Test
    public void givenRealOpenSearch_whenHeartbeatPushed_thenTimestampIsMappedAsDate() throws Exception {
        heartbeatService.sendHeartbeat();
        awaitDocumentCount(1);

        // Dotted ECS names are expanded by OpenSearch into nested objects, so the mapping has to be walked
        // rather than read off a flat key. If a time filter cannot bind to these, the index is useless in
        // Dashboards, which is the entire point of pushing the documents.
        JsonNode mapping = JacksonUtil.toJsonNode(send("GET", "/" + index + "/_mapping", null));
        JsonNode properties = mapping.get(index).get("mappings").get("properties");
        assertThat(properties.get("@timestamp").get("type").asText()).isEqualTo("date");
        assertThat(properties.get("process").get("properties").get("start_time").get("type").asText())
                .isEqualTo("date");
        assertThat(properties.get("process").get("properties").get("uptime")
                .get("properties").get("ms").get("type").asText()).isEqualTo("long");
        assertThat(properties.get("sequence").get("type").asText()).isEqualTo("long");
        // Grouping heartbeats per instance needs a keyword subfield: dynamic mapping makes service.id text,
        // so a terms aggregation has to target service.id.keyword.
        assertThat(properties.get("service").get("properties").get("id")
                .get("fields").get("keyword").get("type").asText()).isEqualTo("keyword");
    }

    /**
     * Waits for the expected number of documents, reporting the raw OpenSearch response on failure. A bare
     * boolean wait here just times out and says nothing about why.
     */
    private void awaitDocumentCount(long expected) {
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            String countBody = rawCount();
            assertThat(heartbeatService.getConsecutiveFailures())
                    .withFailMessage("heartbeat push failed; last count response: %s", countBody)
                    .isZero();
            assertThat(documentCount(countBody))
                    .withFailMessage("expected %d document(s), count response: %s", expected, countBody)
                    .isEqualTo(expected);
        });
    }

    private String rawCount() throws IOException, InterruptedException {
        send("POST", "/" + index + "/_refresh", "");
        return send("GET", "/" + index + "/_count", null);
    }

    private long documentCount(String countBody) {
        JsonNode result = JacksonUtil.toJsonNode(countBody);
        JsonNode count = result == null ? null : result.get("count");
        // A missing index answers 404 with an error body and no count; treat that as "nothing indexed yet".
        return count == null ? -1 : count.asLong();
    }

    private JsonNode firstDocument() throws IOException, InterruptedException {
        JsonNode result = JacksonUtil.toJsonNode(send("GET", "/" + index + "/_search?size=1", null));
        return result.get("hits").get("hits").get(0).get("_source");
    }

    private String send(String method, String path, String body) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json");
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = probe.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

}
