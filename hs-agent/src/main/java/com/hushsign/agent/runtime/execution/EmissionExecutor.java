package com.hushsign.agent.runtime.execution;

import com.hushsign.agent.runtime.kafka.EmissionConsumer;
import com.hushsign.agent.runtime.kafka.EmissionHandler;
import com.hushsign.agent.runtime.transmission.TransmissionProducer;
import com.hushsign.agent.technique.Technique;
import com.hushsign.agent.technique.TechniqueParams;
import com.hushsign.protocol.ProtocolJson;
import com.hushsign.protocol.v1.EmissionCommand;
import com.hushsign.protocol.v1.TransmissionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * P1-S13: the terminal {@link EmissionHandler} — one validated emission in,
 * one immutable transmission out.
 *
 * <p>Order of gates per plan §5.2: expiry first (an expired emission is never
 * sent, outcome {@code EXPIRED}), then cancellation (the agent drops work the
 * platform cancelled while it was queued, outcome {@code CANCELLED}), then
 * technique resolution and PDU build (outcome {@code FAILED} when the technique
 * is not implemented or the number is unusable), then the modem send via
 * {@link PduSender} ({@code ACCEPTED} or {@code FAILED} with the AT error as
 * cause). Policy and dedupe run upstream of this handler in the chain.
 *
 * <p>Payloads discarded by the protocol gate are reported too: a command the
 * freshness gate refused becomes an {@code EXPIRED} transmission, so the
 * platform learns why nothing was sent instead of waiting for a retry timeout.
 */
public final class EmissionExecutor implements EmissionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(EmissionExecutor.class);

    private final PduSender sender;
    private final TransmissionProducer transmissions;
    private final Clock clock;

    public EmissionExecutor(PduSender sender, TransmissionProducer transmissions) {
        this(sender, transmissions, Clock.systemUTC());
    }

    public EmissionExecutor(PduSender sender, TransmissionProducer transmissions, Clock clock) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.transmissions = Objects.requireNonNull(transmissions, "transmissions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void onCommand(EmissionCommand command) {
        Instant requestedAt = Instant.now(clock);
        if (command.expiresAt() == null || !requestedAt.isBefore(command.expiresAt())) {
            LOG.info("emission {} expired before send - reporting EXPIRED", command.emissionId());
            report(command, TransmissionResult.Outcome.EXPIRED, null, null, requestedAt, false);
            return;
        }

        Technique technique;
        try {
            technique = Technique.fromWireCode(command.technique());
        } catch (IllegalArgumentException e) {
            report(command, TransmissionResult.Outcome.FAILED, e.getMessage(), null, requestedAt, false);
            return;
        }

        String pdu;
        try {
            pdu = technique.generate(digitsOf(command.msisdn()), technique.defaultParams());
        } catch (IllegalArgumentException e) {
            report(command, TransmissionResult.Outcome.FAILED, "cannot build PDU: " + e.getMessage(),
                    null, requestedAt, false);
            return;
        }

        try {
            sender.send(pdu);
        } catch (Exception e) {
            LOG.warn("send failed for emission {}: {}", command.emissionId(), e.getMessage());
            report(command, TransmissionResult.Outcome.FAILED,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                    pdu, requestedAt, false);
            return;
        }
        report(command, TransmissionResult.Outcome.ACCEPTED, null, pdu, requestedAt, true);
    }

    @Override
    public void onRejected(String rawPayload, EmissionConsumer.RejectReason reason) {
        if (reason == EmissionConsumer.RejectReason.INVALID) {
            // No trustworthy identity in the payload — there is nothing to record.
            LOG.debug("emission payload discarded before execution ({})", reason);
            return;
        }
        try {
            EmissionCommand command = ProtocolJson.read(rawPayload, EmissionCommand.class);
            LOG.info("emission {} refused by the freshness gate ({}) - reporting EXPIRED",
                    command.emissionId(), reason);
            report(command, TransmissionResult.Outcome.EXPIRED,
                    "refused by the freshness gate: " + reason, null, Instant.now(clock), false);
        } catch (RuntimeException e) {
            LOG.warn("cannot report a {} emission refusal: {}", reason, e.getMessage());
        }
    }

    /**
     * Reports an outcome, turning a reporting failure into
     * {@link EmissionExecutionException} so the consumer neither commits nor
     * (when the modem was already touched) allows a duplicate send.
     */
    private void report(EmissionCommand command, String outcome, String failureCause,
                        String pduHex, Instant requestedAt, boolean sent) {
        try {
            transmissions.report(command, outcome, failureCause, pduHex, requestedAt);
        } catch (RuntimeException e) {
            throw new EmissionExecutionException(sent,
                    "cannot report " + outcome + " for emission " + command.emissionId(), e);
        }
    }

    /** E.164 destination without the leading '+' (the technique generators want bare digits). */
    static String digitsOf(String msisdn) {
        String digits = msisdn == null ? "" : msisdn;
        if (digits.startsWith("+")) {
            digits = digits.substring(1);
        }
        return digits;
    }
}
