package com.hushsign.agent.runtime.kafka;

import com.hushsign.agent.config.AgentConfig;
import com.hushsign.agent.runtime.spool.SpoolSender;
import com.hushsign.agent.runtime.spool.SpooledRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * P1-S13: publishes spooled records to the real broker. Every send blocks
 * until the broker acknowledges (bounded by {@code sendTimeout}), so a
 * failing broker surfaces as an exception — the spool's flush stops at the
 * first failure and nothing is acked that wasn't delivered.
 */
public final class KafkaSpoolSender implements SpoolSender, AutoCloseable {

    private static final Duration DEFAULT_SEND_TIMEOUT = Duration.ofSeconds(10);

    private final KafkaProducer<String, String> producer;
    private final Duration sendTimeout;

    public KafkaSpoolSender(String bootstrapServers) {
        this(new KafkaProducer<>(KafkaClientSettings.producer(new AgentConfig.KafkaConfig(
                        bootstrapServers, null,
                        new AgentConfig.SecurityConfig("PLAINTEXT", null, null, null, null, null)))),
                DEFAULT_SEND_TIMEOUT);
    }

    public KafkaSpoolSender(AgentConfig.KafkaConfig kafka) {
        this(new KafkaProducer<>(KafkaClientSettings.producer(kafka)), DEFAULT_SEND_TIMEOUT);
    }

    public KafkaSpoolSender(KafkaProducer<String, String> producer, Duration sendTimeout) {
        this.producer = producer;
        this.sendTimeout = sendTimeout;
    }

    @Override
    public void send(SpooledRecord record) throws Exception {
        ProducerRecord<String, String> out = new ProducerRecord<>(record.topic(), record.key(), record.value());
        producer.send(out).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        producer.close();
    }
}
