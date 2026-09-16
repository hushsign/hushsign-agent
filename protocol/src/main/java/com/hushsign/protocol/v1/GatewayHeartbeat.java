package com.hushsign.protocol.v1;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Agent → platform: periodic health signal (modems, SIMs, counters, config hashes).
 * Topic: {@code hs.fleet.heartbeat.v1}, keyed by gatewayId.
 *
 * <p>Data minimization: IMSI/ICCID only as hashes, MSISDN only masked.
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
        Map<String, Long> counters) {

    /** One modem attached to the gateway. */
    @JsonIgnoreProperties(ignoreUnknown = true)
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
