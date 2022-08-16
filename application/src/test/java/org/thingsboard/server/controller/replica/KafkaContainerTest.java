/**
 * Copyright © 2016-2022 The Thingsboard Authors
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
package org.thingsboard.server.controller.replica;

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.state.KeyValueStore;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.PullPolicy;
import org.testcontainers.lifecycle.Startables;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class KafkaContainerTest {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(KafkaContainerTest.class);
    @ClassRule
    public static GenericContainer<?> zookeeper = new GenericContainer<>("bitnami/zookeeper")
            .withImagePullPolicy(PullPolicy.ageBased(Duration.of(1, ChronoUnit.DAYS)))
            .withLogConsumer(new Slf4jLogConsumer(log).withSeparateOutputStreams())
            .withEnv("ZOO_ENABLE_ADMIN_SERVER", "no")
            .withEnv("ALLOW_ANONYMOUS_LOGIN", "yes")
            .withNetworkMode("host")
            .withTmpFs(Map.of("/bitnami/zookeeper","rw"))
            .waitingFor(Wait.forListeningPort())
            ;

    @ClassRule
    public static GenericContainer<?> kafka = new GenericContainer<>("bitnami/kafka:3.2")
            .withLogConsumer(new Slf4jLogConsumer(log).withSeparateOutputStreams())
            .withEnv("KAFKA_CFG_ZOOKEEPER_CONNECT", "localhost:2181")
            .withEnv("ALLOW_PLAINTEXT_LISTENER", "yes")
            .withNetworkMode("host")
            .withTmpFs(Map.of("/bitnami/kafka","rw"))
            .dependsOn(zookeeper)
            .waitingFor(Wait.forListeningPort())
            ;

    @BeforeClass
    public static void beforeClass() throws Exception {
        Startables.deepStart(zookeeper, kafka).join();
    }

    @Before
    public void setUp() throws Exception {
       // log.error("network {}", network.getId());
        log.error("zookeeper network alias {}", zookeeper.getNetworkAliases().get(0));
        log.error("zookeeper exposed ports {}", zookeeper.getExposedPorts());
        log.error("zookeeper host {}", zookeeper.getHost());
        log.error("kafka network alias {}", kafka.getNetworkAliases().get(0));
        log.error("kafka exposed ports {}", kafka.getExposedPorts());
        log.error("kafka host {}", kafka.getHost());

    }

    @After
    public void tearDown() throws Exception {

    }

    @Test
    public void kafkaStreamTest() {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "tb-app");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        //props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);

        StreamsBuilder builder = new StreamsBuilder();

        KStream<String, String> textLines = builder.stream("tb_replica");
        KTable<String, Long> wordCounts = textLines
                .peek(log::info)
                .flatMapValues(textLine -> Arrays.asList(textLine.toLowerCase().split("\\W+")))
                .groupBy((key, word) -> word)
                .count(Materialized.<String, Long, KeyValueStore<Bytes, byte[]>>as("counts-store"));
        wordCounts
                .toStream()
                .to("WordsWithCountsTopic", Produced.with(Serdes.String(), Serdes.Long()));

        Topology topology = builder.build();
        log.info("topology {}", topology.describe());
        KafkaStreams streams = new KafkaStreams(topology, props);
        streams.start();
    }
}
