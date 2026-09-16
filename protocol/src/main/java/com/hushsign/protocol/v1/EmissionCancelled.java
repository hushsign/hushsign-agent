package com.hushsign.protocol.v1;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * Platform → agent: best-effort cancellation of an emission that has not started yet.
 * Topic: {@code hs.emissions.cancelled.v1}, keyed by emissionId.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmissionCancelled(
        String protocolVersion,
        String emissionId,
        Instant issuedAt) {
}
