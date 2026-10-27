package com.hushsign.agent.runtime.kafka;

import com.hushsign.agent.config.AgentConfig;

import java.util.Properties;
import java.util.Set;

/**
 * Translates {@code agent.yaml} {@code kafka.*} (including TLS/SASL) into the
 * properties every Kafka client of the agent needs.
 *
 * <p>Before this class existed the security block was parsed and then dropped on
 * the floor: the consumers and the producer received only the bootstrap servers,
 * so a gateway enrolled with TLS + SCRAM could not connect and a config claiming
 * {@code SASL_SSL} silently ran {@code PLAINTEXT}.
 *
 * <p>Incomplete security settings fail fast at startup instead of degrading to an
 * unencrypted connection.
 */
public final class KafkaClientSettings {

    private static final Set<String> PROTOCOLS = Set.of("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL");
    private static final String SCRAM_LOGIN_MODULE = "org.apache.kafka.common.security.scram.ScramLoginModule";
    private static final String PLAIN_LOGIN_MODULE = "org.apache.kafka.common.security.plain.PlainLoginModule";

    private KafkaClientSettings() {
    }

    /**
     * Bootstrap, client id and security properties shared by consumers and producers.
     *
     * @throws IllegalStateException when the security block is incomplete or unknown
     */
    public static Properties common(AgentConfig.KafkaConfig kafka) {
        if (kafka == null) {
            throw new IllegalStateException("kafka configuration is missing");
        }
        String bootstrap = requireText(kafka.bootstrapServers(), "kafka.bootstrapServers");
        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrap);
        if (kafka.clientId() != null && !kafka.clientId().isBlank()) {
            props.put("client.id", kafka.clientId());
        }

        AgentConfig.SecurityConfig security = kafka.security();
        String protocol = security == null || security.protocol() == null
                ? "PLAINTEXT" : security.protocol().trim().toUpperCase(java.util.Locale.ROOT);
        if (!PROTOCOLS.contains(protocol)) {
            throw new IllegalStateException("kafka.security.protocol must be one of " + PROTOCOLS
                    + ", got '" + protocol + "'");
        }
        props.put("security.protocol", protocol);

        // One early exit instead of a null check the analyser cannot follow: a security block is
        // only optional while the protocol stays PLAINTEXT, so everything below may assume it
        // exists (java:S2259).
        if (security == null) {
            if (protocol.startsWith("SASL") || protocol.endsWith("SSL")) {
                throw new IllegalStateException("kafka.security is required for protocol " + protocol);
            }
            return props;
        }

        if (protocol.startsWith("SASL")) {
            String mechanism = requireText(security.saslMechanism(), "kafka.security.saslMechanism");
            String username = requireText(security.saslUsername(), "kafka.security.saslUsername");
            String password = requireText(security.saslPassword(), "kafka.security.saslPassword");
            props.put("sasl.mechanism", mechanism);
            props.put("sasl.jaas.config", jaasConfig(mechanism, username, password));
        }
        if (protocol.endsWith("SSL")) {
            String truststore = requireText(security.truststorePath(), "kafka.security.truststorePath");
            props.put("ssl.truststore.location", truststore);
            if (security.truststorePassword() != null && !security.truststorePassword().isBlank()) {
                props.put("ssl.truststore.password", security.truststorePassword());
            }
        }
        return props;
    }

    /** Consumer properties: {@link #common} plus group, deserializers and offset policy. */
    public static Properties consumer(AgentConfig.KafkaConfig kafka, String groupId,
                                      String autoOffsetReset, int maxPollRecords) {
        Properties props = common(kafka);
        props.put("group.id", groupId);
        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("enable.auto.commit", "false");
        props.put("auto.offset.reset", autoOffsetReset);
        props.put("max.poll.records", Integer.toString(maxPollRecords));
        // One modem serializes every send, so a batch is processed one record at a
        // time; the session must survive the longest possible serial operation
        // (response timeout × attempts) instead of triggering a rebalance in the
        // middle of it. The default 5 min is too tight for a 30 s modem timeout.
        props.put("max.poll.interval.ms", Integer.toString(MAX_POLL_INTERVAL_MS));
        return props;
    }

    /** How long a single poll iteration may take before the broker rebalances. */
    public static final int MAX_POLL_INTERVAL_MS = 600_000;

    /** Producer properties: {@link #common} plus serializers and {@code acks=all}. */
    public static Properties producer(AgentConfig.KafkaConfig kafka) {
        Properties props = common(kafka);
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("acks", "all");
        return props;
    }

    private static String jaasConfig(String mechanism, String username, String password) {
        String module = mechanism.toUpperCase(java.util.Locale.ROOT).startsWith("SCRAM")
                ? SCRAM_LOGIN_MODULE : PLAIN_LOGIN_MODULE;
        return module + " required username=\"" + escape(username) + "\" password=\"" + escape(password) + "\";";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String requireText(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is required by the configured Kafka security settings");
        }
        return value.trim();
    }
}
