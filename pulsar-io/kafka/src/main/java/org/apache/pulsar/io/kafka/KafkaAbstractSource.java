/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.io.kafka;

import io.jsonwebtoken.io.Encoders;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.header.Header;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.common.schema.KeyValue;
import org.apache.pulsar.common.schema.KeyValueEncodingType;
import org.apache.pulsar.functions.api.KVRecord;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.SourceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple Kafka Source to transfer messages from a Kafka topic.
 */
public abstract class KafkaAbstractSource<V> extends KafkaPushSource<V> {
    public static final String HEADER_KAFKA_TOPIC_KEY = "__kafka_topic";
    public static final String HEADER_KAFKA_PTN_KEY = "__kafka_partition";
    public static final String HEADER_KAFKA_OFFSET_KEY = "__kafka_offset";

    private static final Logger LOG = LoggerFactory.getLogger(KafkaAbstractSource.class);

    private volatile Consumer<Object, Object> consumer;
    private volatile boolean running = false;
    private KafkaSourceConfig kafkaSourceConfig;
    private Thread runnerThread;
    private long maxPollIntervalMs;

    @Override
    public void open(Map<String, Object> config, SourceContext sourceContext) throws Exception {
        kafkaSourceConfig = KafkaSourceConfig.load(config, sourceContext);
        Objects.requireNonNull(kafkaSourceConfig.getTopic(), "Kafka topic is not set");
        Objects.requireNonNull(kafkaSourceConfig.getBootstrapServers(), "Kafka bootstrapServers is not set");
        Objects.requireNonNull(kafkaSourceConfig.getGroupId(), "Kafka consumer group id is not set");
        if (kafkaSourceConfig.getFetchMinBytes() <= 0) {
            throw new IllegalArgumentException("Invalid Kafka Consumer fetchMinBytes : "
                + kafkaSourceConfig.getFetchMinBytes());
        }
        if (kafkaSourceConfig.isAutoCommitEnabled() && kafkaSourceConfig.getAutoCommitIntervalMs() <= 0) {
            throw new IllegalArgumentException("Invalid Kafka Consumer autoCommitIntervalMs : "
                + kafkaSourceConfig.getAutoCommitIntervalMs());
        }
        if (kafkaSourceConfig.getSessionTimeoutMs() <= 0) {
            throw new IllegalArgumentException("Invalid Kafka Consumer sessionTimeoutMs : "
                + kafkaSourceConfig.getSessionTimeoutMs());
        }
        if (kafkaSourceConfig.getHeartbeatIntervalMs() <= 0) {
            throw new IllegalArgumentException("Invalid Kafka Consumer heartbeatIntervalMs : "
                    + kafkaSourceConfig.getHeartbeatIntervalMs());
        }

        Properties props = new Properties();
        if (kafkaSourceConfig.getConsumerConfigProperties() != null) {
            props.putAll(kafkaSourceConfig.getConsumerConfigProperties());
        }
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaSourceConfig.getBootstrapServers());
        if (StringUtils.isNotEmpty(kafkaSourceConfig.getSecurityProtocol())) {
            props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, kafkaSourceConfig.getSecurityProtocol());
        }
        if (StringUtils.isNotEmpty(kafkaSourceConfig.getSaslMechanism())) {
            props.put(SaslConfigs.SASL_MECHANISM, kafkaSourceConfig.getSaslMechanism());
        }
        if (StringUtils.isNotEmpty(kafkaSourceConfig.getSaslJaasConfig())) {
            props.put(SaslConfigs.SASL_JAAS_CONFIG, kafkaSourceConfig.getSaslJaasConfig());
        }
        if (StringUtils.isNotEmpty(kafkaSourceConfig.getSslEnabledProtocols())) {
            props.put(SslConfigs.SSL_ENABLED_PROTOCOLS_CONFIG, kafkaSourceConfig.getSslEnabledProtocols());
        }
        if (StringUtils.isNotEmpty(kafkaSourceConfig.getSslEndpointIdentificationAlgorithm())) {
            props.put(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG,
                    kafkaSourceConfig.getSslEndpointIdentificationAlgorithm());
        }
        if (StringUtils.isNotEmpty(kafkaSourceConfig.getSslTruststoreLocation())) {
            props.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, kafkaSourceConfig.getSslTruststoreLocation());
        }
        if (StringUtils.isNotEmpty(kafkaSourceConfig.getSslTruststorePassword())) {
            props.put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, kafkaSourceConfig.getSslTruststorePassword());
        }
        props.put(ConsumerConfig.GROUP_ID_CONFIG, kafkaSourceConfig.getGroupId());
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, String.valueOf(kafkaSourceConfig.getFetchMinBytes()));
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                String.valueOf(kafkaSourceConfig.isAutoCommitEnabled()));
        props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG,
                String.valueOf(kafkaSourceConfig.getAutoCommitIntervalMs()));
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, String.valueOf(kafkaSourceConfig.getSessionTimeoutMs()));
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG,
                String.valueOf(kafkaSourceConfig.getHeartbeatIntervalMs()));
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, kafkaSourceConfig.getAutoOffsetReset());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, kafkaSourceConfig.getKeyDeserializationClass());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, kafkaSourceConfig.getValueDeserializationClass());
        if (props.containsKey(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG)) {
            maxPollIntervalMs = Long.parseLong(props.get(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG).toString());
        } else {
            maxPollIntervalMs = Long.parseLong(
                    ConsumerConfig.configDef().defaultValues().get(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG)
                            .toString());
        }
        try {
            consumer = new KafkaConsumer<>(beforeCreateConsumer(props));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to instantiate Kafka consumer", ex);
        }
        this.start();
    }

    protected Properties beforeCreateConsumer(Properties props) {
        return props;
    }

    @Override
    public void close() throws InterruptedException {
        LOG.info("Stopping kafka source");
        running = false;
        if (runnerThread != null) {
            runnerThread.interrupt();
            runnerThread.join();
            runnerThread = null;
        }
        if (consumer != null) {
            consumer.close();
            consumer = null;
        }
        LOG.info("Kafka source stopped.");
    }

    @SuppressWarnings("unchecked")
    public void start() {
        LOG.info("Starting subscribe kafka source on {}", kafkaSourceConfig.getTopic());
        consumer.subscribe(Collections.singletonList(kafkaSourceConfig.getTopic()));
        runnerThread = new Thread(() -> {
            LOG.info("Kafka source started.");
            while (running) {
                try {
                    ConsumerRecords<Object, Object> consumerRecords = consumer.poll(Duration.ofSeconds(1L));
                    CompletableFuture<?>[] futures = new CompletableFuture<?>[consumerRecords.count()];
                    int index = 0;
                    for (ConsumerRecord<Object, Object> consumerRecord : consumerRecords) {
                        KafkaRecord record = buildRecord(consumerRecord);
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Write record {} {} {}", record.getKey(), record.getValue(), record.getSchema());
                        }
                        consume(record);
                        futures[index] = record.getCompletableFuture();
                        index++;
                    }
                    if (!kafkaSourceConfig.isAutoCommitEnabled()) {
                        // Wait about 2/3 of the time of maxPollIntervalMs.
                        // so as to avoid waiting for the timeout to be kicked out of the consumer group.
                        CompletableFuture.allOf(futures).get(maxPollIntervalMs * 2 / 3, TimeUnit.MILLISECONDS);
                        consumer.commitSync();
                    }
                } catch (Exception e) {
                    LOG.error("Error while processing records", e);
                    notifyError(e);
                    break;
                }
            }
        });
        running = true;
        runnerThread.setName("Kafka Source Thread");
        runnerThread.start();
    }

    public abstract KafkaRecord buildRecord(ConsumerRecord<Object, Object> consumerRecord);

    protected Map<String, String> copyKafkaHeaders(ConsumerRecord<Object, Object> consumerRecord) {
        if (!kafkaSourceConfig.isCopyHeadersEnabled()) {
            return Collections.emptyMap();
        }
        Map<String, String> properties = new HashMap<>();
        properties.put(HEADER_KAFKA_TOPIC_KEY, consumerRecord.topic());
        properties.put(HEADER_KAFKA_PTN_KEY, Integer.toString(consumerRecord.partition()));
        properties.put(HEADER_KAFKA_OFFSET_KEY, Long.toString(consumerRecord.offset()));
        for (Header header: consumerRecord.headers()) {
            properties.put(header.key(), Encoders.BASE64.encode(header.value()));
        }
        return properties;
    }

    @Slf4j
    protected static class KafkaRecord<V> implements Record<V> {
        private final ConsumerRecord<String, ?> record;
        private final V value;
        private final Schema<V> schema;
        private final Map<String, String> properties;

        @Getter
        private final CompletableFuture<Void> completableFuture = new CompletableFuture<>();

        public KafkaRecord(ConsumerRecord<String, ?> record, V value, Schema<V> schema,
                           Map<String, String> properties) {
            this.record = record;
            this.value = value;
            this.schema = schema;
            this.properties = properties;
        }
        @Override
        public Optional<String> getPartitionId() {
            return Optional.of(Integer.toString(record.partition()));
        }

        @Override
        public Optional<Integer> getPartitionIndex() {
            return Optional.of(record.partition());
        }

        @Override
        public Optional<Long> getRecordSequence() {
            return Optional.of(record.offset());
        }

        @Override
        public Optional<String> getKey() {
            return Optional.ofNullable(record.key());
        }

        @Override
        public V getValue() {
            return value;
        }

        @Override
        public void ack() {
            completableFuture.complete(null);
        }

        @Override
        public void fail() {
            completableFuture.completeExceptionally(
                    new RuntimeException(
                            String.format(
                                    "Failed to process record with kafka topic: %s partition: %d offset: %d key: %s",
                                    record.topic(),
                                    record.partition(),
                                    record.offset(),
                                    getKey()
                            )
                    )
            );
        }

        @Override
        public Schema<V> getSchema() {
            return schema;
        }

        @Override
        public Map<String, String> getProperties(){
            return properties;
        }
    }
    protected static class KeyValueKafkaRecord<V> extends KafkaRecord implements KVRecord<Object, Object> {

        private final Schema<Object> keySchema;
        private final Schema<Object> valueSchema;

        public KeyValueKafkaRecord(ConsumerRecord record, KeyValue value,
                                   Schema<Object> keySchema, Schema<Object> valueSchema,
                                   Map<String, String> properties) {
            super(record, value, null, properties);
            this.keySchema = keySchema;
            this.valueSchema = valueSchema;
        }

        @Override
        public Schema<Object> getKeySchema() {
            return keySchema;
        }

        @Override
        public Schema<Object> getValueSchema() {
            return valueSchema;
        }

        @Override
        public KeyValueEncodingType getKeyValueEncodingType() {
            return KeyValueEncodingType.SEPARATED;
        }
    }
}
