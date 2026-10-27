package com.hushsign.protocol.v1;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Agent → platform: periodic health signal (modems, SIMs, counters, config hashes).
 * Topic: {@code hs.fleet.heartbeat.v1}, keyed by gatewayId.
 *
 * <p>Data minimization: IMSI/ICCID only as hashes, MSISDN only masked.
 *
 * <p>{@code sequence} restarts at 0 with every process, so it is only monotonic
 * within one run; {@code bootId} identifies the run and makes a sequence gap
 * attributable to a restart instead of a lost heartbeat. {@code supportedTechniques}
 * is the catalog this build can actually send (the platform must not schedule a
 * technique the gateway would refuse).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GatewayHeartbeat(
        String protocolVersion,
        String gatewayId,
        String version,
        String policyHash,
        String configHash,
        long sequence,
        Long uptimeSeconds,
        Instant sentAt,
        List<ModemInfo> modems,
        List<SimInfo> sims,
        Map<String, Long> counters,
        String bootId,
        List<String> supportedTechniques,
        // Absent, not null: the schema types policy as an object, and Jackson would write
        // "policy": null from an agent that predates the field - the same defect B29 was.
        @JsonInclude(JsonInclude.Include.NON_NULL) PolicyInfo policy) {

    /**
     * The rules the agent is enforcing right now (P7-S13).
     *
     * <p>{@code policyHash} only says the file changed; this says what it now says. Without it
     * a console can report "the device refused: 6-per-hour cap" but cannot answer the next
     * question - is it still six? - because the number lives in a file on the device.
     *
     * <p>{@code source} is the honest part: REMOTE means the platform changed something and the
     * value is an overlay on the file, LOCAL means the file is the whole story.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PolicyInfo(
            boolean killSwitch,
            Caps caps,
            List<String> techniques,
            List<String> operators,
            QuietHours quietHours,
            Long maxCommandAgeSeconds,
            String source,
            List<String> remoteAllowed) {

        public static final class Source {
            public static final String LOCAL = "LOCAL";
            public static final String REMOTE = "REMOTE";

            private Source() {
            }
        }

        /** Hourly ceilings; 0 means no ceiling. */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Caps(int perTargetPerHour, int perOperatorPerHour, int perGatewayPerHour) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record QuietHours(String start, String end, String zone) {
        }
    }

    /**
     * One modem attached to the gateway.
     *
     * <p>{@code NON_NULL} because the wire schema types {@code memoryStore} and {@code portHint}
     * as strings: Jackson wrote {@code "memoryStore": null} and the validator rejected the whole
     * heartbeat, so a gateway with a modem that has no SMS memory store reported nothing at all.
     * Omitting an absent optional field is what the schema expects.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ModemInfo(
            String serial,
            String model,
            String manufacturer,
            String status,
            String portHint,
            String memoryStore,
            Integer signalQuality) {

        public static final class Status {
            public static final String READY = "READY";
            public static final String BUSY = "BUSY";
            public static final String DEGRADED = "DEGRADED";
            public static final String ERROR = "ERROR";

            private Status() {
            }
        }
    }

    /** One SIM currently inserted (or recently seen) on the gateway. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SimInfo(
            String iccidHash,
            String imsiHash,
            String operator,
            String label,
            Boolean active,
            String modemSerial,
            String msisdnMasked) {
    }
}
