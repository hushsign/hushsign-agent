package com.hushsign.agent.config;

import java.util.List;

/**
 * The parsed {@code agent.yaml} (P1-S10). Every field can be overridden by an
 * environment variable ({@code HS_} + underscored path, e.g.
 * {@code HS_KAFKA_BOOTSTRAP_SERVERS}); env always wins over the file.
 *
 * @param gatewayId  the gateway id this agent reports as (1-128 chars)
 * @param operators  MCC-MNC operators this gateway serves (topics hs.emissions.{op})
 * @param workDir    base directory for spool/dedupe relative paths
 */
public record AgentConfig(
        String gatewayId,
        List<String> operators,
        String workDir,
        KafkaConfig kafka,
        ModemsConfig modems,
        HeartbeatConfig heartbeat,
        DedupeConfig dedupe,
        SpoolConfig spool,
        PolicyConfig policy) {

    public record KafkaConfig(
            String bootstrapServers,
            String clientId,
            SecurityConfig security) {
    }

    public record SecurityConfig(
            String protocol,
            String saslMechanism,
            String saslUsername,
            String saslPassword,
            String truststorePath) {
    }

    public record ModemsConfig(
            boolean autoScan,
            List<String> ports,
            int baudRate,
            int responseTimeoutMs,
            int commandWaitMs,
            int pollWaitMs,
            int probeAttempts,
            int maxConsecutiveErrors) {
    }

    public record HeartbeatConfig(int intervalSeconds) {
    }

    public record DedupeConfig(int maxEntries) {
    }

    public record SpoolConfig(String path) {
    }

    public record PolicyConfig(String path) {
    }
}
