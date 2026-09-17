package com.hushsign.agent.runtime.transmission;

import com.hushsign.agent.runtime.heartbeat.Hashes;
import com.hushsign.agent.runtime.spool.Spool;
import com.hushsign.protocol.MessageType;
import com.hushsign.protocol.ProtocolJson;
import com.hushsign.protocol.ProtocolValidator;
import com.hushsign.protocol.Topics;
import com.hushsign.protocol.v1.EmissionCommand;
import com.hushsign.protocol.v1.TransmissionResult;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * P1-S13: turns one execution outcome into an immutable
 * {@link TransmissionResult} and spools it to {@code hs.transmissions.v1}
 * (keyed by emissionId) via the P1-S8 spool — when the broker is down,
 * results queue on disk and flush in order on reconnect.
 *
 * <p>Every record is self-validated against the vendored JSON Schema before
 * spooling (fail fast, never spool garbage); {@code failureCause} is
 * truncated to the wire limit of 255 chars.
 */
public final class TransmissionProducer {

    private final String gatewayId;
    private final String modemId;
    private final String simId;
    private final Spool spool;
    private final Clock clock;
    private final ProtocolValidator validator = new ProtocolValidator();

    public TransmissionProducer(String gatewayId, String modemId, String simId, Spool spool) {
        this(gatewayId, modemId, simId, spool, Clock.systemUTC());
    }

    public TransmissionProducer(String gatewayId, String modemId, String simId, Spool spool, Clock clock) {
        this.gatewayId = Objects.requireNonNull(gatewayId, "gatewayId");
        this.modemId = modemId;
        this.simId = simId;
        this.spool = Objects.requireNonNull(spool, "spool");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Builds, validates and spools one transmission result.
     *
     * @param command      the emission this attempt belongs to
     * @param outcome      a {@link TransmissionResult.Outcome} constant
     * @param failureCause optional cause for {@code FAILED} attempts (255 max)
     * @param pduHex       the PDU that was built for the attempt (null when none)
     * @param requestedAt  when the agent started the attempt
     * @return the spooled transmission result
     */
    public TransmissionResult report(EmissionCommand command, String outcome,
                                     String failureCause, String pduHex, Instant requestedAt) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(requestedAt, "requestedAt");
        TransmissionResult result = new TransmissionResult(
                "1",
                UUID.randomUUID().toString(),
                command.emissionId(),
                command.attempt(),
                outcome,
                failureCause == null ? null : truncate(failureCause),
                pduHex == null ? null : Hashes.sha256(pduHex),
                command.operator(),
                gatewayId,
                modemId,
                simId,
                requestedAt,
                Instant.now(clock));
        String json = ProtocolJson.write(result);
        validator.validate(MessageType.TRANSMISSION_RESULT, json); // fail before spooling garbage
        spool.enqueue(Topics.TRANSMISSIONS, command.emissionId(), json);
        return result;
    }

    private static String truncate(String cause) {
        return cause.length() > 255 ? cause.substring(0, 255) : cause;
    }
}
