package com.hushsign.protocol.v1;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * Agent → platform: optional network delivery report. Silent pings often never
 * produce DRs, so nothing in the platform may depend on them.
 * Topic: {@code hs.reports.delivery.v1}, keyed by transmissionId.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeliveryReport(
        String protocolVersion,
        String transmissionId,
        String status,
        String cause,
        Instant receivedAt) {

    /** Delivery status. Append-only. */
    public static final class Status {
        public static final String DELIVERED = "DELIVERED";
        public static final String UNDELIVERED = "UNDELIVERED";
        public static final String UNKNOWN = "UNKNOWN";

        private Status() {
        }
    }
}
