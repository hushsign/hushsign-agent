package com.hushsign.agent.runtime.kafka;

import com.hushsign.agent.config.AgentConfig;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the "security config is parsed but never applied" defect:
 * every Kafka client must receive the TLS/SASL block, and an incomplete security
 * block must fail at startup instead of silently downgrading to PLAINTEXT.
 */
class KafkaClientSettingsTest {

    @Test
    void plaintextCarriesBootstrapAndClientId() {
        Properties props = KafkaClientSettings.common(kafka("PLAINTEXT",
                null, null, null, null, null));

        assertEquals("kafka:9092", props.get("bootstrap.servers"));
        assertEquals("gw-buc-01", props.get("client.id"));
        assertEquals("PLAINTEXT", props.get("security.protocol"));
    }

    @Test
    void saslScramOverTlsIsFullyWired() {
        Properties props = KafkaClientSettings.common(kafka("SASL_SSL",
                "SCRAM-SHA-512", "gw-226-10", "topsecret", "/etc/hushsign/kafka.p12", "changeit"));

        assertEquals("SASL_SSL", props.get("security.protocol"));
        assertEquals("SCRAM-SHA-512", props.get("sasl.mechanism"));
        String jaas = (String) props.get("sasl.jaas.config");
        assertTrue(jaas.contains("org.apache.kafka.common.security.scram.ScramLoginModule required"),
                "SCRAM must use the SCRAM login module, got: " + jaas);
        assertTrue(jaas.contains("username=\"gw-226-10\""), jaas);
        assertTrue(jaas.contains("password=\"topsecret\""), jaas);
        assertEquals("/etc/hushsign/kafka.p12", props.get("ssl.truststore.location"));
        assertEquals("changeit", props.get("ssl.truststore.password"));
    }

    @Test
    void plainSaslUsesThePlainLoginModule() {
        Properties props = KafkaClientSettings.common(kafka("SASL_PLAINTEXT",
                "PLAIN", "gw", "secret", null, null));

        assertTrue(((String) props.get("sasl.jaas.config"))
                .contains("org.apache.kafka.common.security.plain.PlainLoginModule"));
    }

    @Test
    void quotesInCredentialsAreEscaped() {
        Properties props = KafkaClientSettings.common(kafka("SASL_PLAINTEXT",
                "PLAIN", "gw\"inject", "pa\\ss", null, null));

        String jaas = (String) props.get("sasl.jaas.config");
        assertTrue(jaas.contains("username=\"gw\\\"inject\""), jaas);
        assertTrue(jaas.contains("password=\"pa\\\\ss\""), jaas);
    }

    @Test
    void saslWithoutCredentialsFailsFast() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> KafkaClientSettings.common(kafka("SASL_SSL",
                        "SCRAM-SHA-512", "gw", null, "/etc/truststore.p12", null)));
        assertTrue(e.getMessage().contains("saslPassword"), e.getMessage());
    }

    @Test
    void sslWithoutTruststoreFailsFast() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> KafkaClientSettings.common(kafka("SSL", null, null, null, "  ", null)));
        assertTrue(e.getMessage().contains("truststorePath"), e.getMessage());
    }

    @Test
    void unknownProtocolFailsFast() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> KafkaClientSettings.common(kafka("PLAINTEXT_TYPO", null, null, null, null, null)));
        assertTrue(e.getMessage().contains("security.protocol"), e.getMessage());
    }

    @Test
    void consumerAndProducerAddTheirOwnDefaults() {
        AgentConfig.KafkaConfig kafka = kafka("PLAINTEXT", null, null, null, null, null);

        Properties consumer = KafkaClientSettings.consumer(kafka, "gw-226-10", "latest", 7);
        assertEquals("gw-226-10", consumer.get("group.id"));
        assertEquals("latest", consumer.get("auto.offset.reset"));
        assertEquals("7", consumer.get("max.poll.records"));
        assertEquals("false", consumer.get("enable.auto.commit"));
        assertEquals(String.valueOf(KafkaClientSettings.MAX_POLL_INTERVAL_MS),
                consumer.get("max.poll.interval.ms"),
                "a serial modem send must not trigger a rebalance mid-batch");
        assertEquals("org.apache.kafka.common.serialization.StringDeserializer",
                consumer.get("value.deserializer"));

        Properties producer = KafkaClientSettings.producer(kafka);
        assertEquals("all", producer.get("acks"));
        assertEquals("org.apache.kafka.common.serialization.StringSerializer",
                producer.get("key.serializer"));
    }

    @Test
    void emissionConsumerPollsOneRecordAtATime() {
        Properties props = EmissionConsumer.consumerProperties(
                kafka("PLAINTEXT", null, null, null, null, null), "226-10");

        assertEquals("1", props.get("max.poll.records"));
        assertEquals("600000", props.get("max.poll.interval.ms"));
        assertEquals("gw-226-10", props.get("group.id"));
    }

    private static AgentConfig.KafkaConfig kafka(String protocol, String mechanism, String user,
                                                 String password, String truststore,
                                                 String truststorePassword) {
        return new AgentConfig.KafkaConfig("kafka:9092", "gw-buc-01",
                new AgentConfig.SecurityConfig(protocol, mechanism, user, password,
                        truststore, truststorePassword));
    }
}
