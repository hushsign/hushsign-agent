package com.hushsign.protocol.v1;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * Agent → platform: hourly summary of the device audit chain (counts and hashes only).
 * Topic: {@code hs.audit.digest.v1}, keyed by gatewayId.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuditDigest(
        String protocolVersion,
        String gatewayId,
        Instant periodStart,
        Instant periodEnd,
        long entryCount,
        String chainHead,
        String chainPrev) {
}
