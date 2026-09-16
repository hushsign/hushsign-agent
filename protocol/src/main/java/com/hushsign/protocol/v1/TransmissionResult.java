package com.hushsign.protocol.v1;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * Agent → platform: outcome of one send attempt. Immutable — corrections are new
 * records with {@code attempt+1}, never edits.
 * Topic: {@code hs.transmissions.v1}, keyed by emissionId.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransmissionResult(
        String protocolVersion,
        String transmissionId,
        String emissionId,
        int attempt,
        String outcome,
        String failureCause,
        String pduSha256,
        String operatorRef,
        String gatewayId,
        String modemId,
        String simId,
        Instant requestedAt,
        Instant completedAt) {

    /** Terminal outcomes a transmission can reach. Append-only. */
    public static final class Outcome {
        public static final String ACCEPTED = "ACCEPTED";
        public static final String REJECTED = "REJECTED";
        public static final String FAILED = "FAILED";
        public static final String EXPIRED = "EXPIRED";
        public static final String CANCELLED = "CANCELLED";
        public static final String BLOCKED_BY_POLICY = "BLOCKED_BY_POLICY";

        private Outcome() {
        }
    }
}
