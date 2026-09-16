package com.hushsign.protocol.v1;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * Platform → agent: execute one technique against one target.
 * Topic: {@code hs.emissions.{operator}} (e.g. {@code hs.emissions.226-10}), keyed by msisdn.
 *
 * <p>Unknown fields are ignored by design: consumers forward-compatible with newer schema versions
 * must tolerate additional properties on the wire.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmissionCommand(
        String protocolVersion,
        String emissionId,
        String scheduleId,
        String warrantRef,
        String targetRef,
        String msisdn,
        String technique,
        String operator,
        int attempt,
        int maxAttempts,
        Instant issuedAt,
        Instant expiresAt) {
}
