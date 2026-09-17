package com.hushsign.agent.runtime.execution;

import com.hushsign.agent.runtime.kafka.EmissionConsumer;
import com.hushsign.agent.runtime.kafka.EmissionHandler;
import com.hushsign.agent.runtime.transmission.TransmissionProducer;
import com.hushsign.agent.technique.Technique;
import com.hushsign.agent.technique.TechniqueParams;
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
 * sent, outcome {@code EXPIRED}), then technique resolution and PDU build
 * (outcome {@code FAILED} when the catalog entry or the number is unusable),
 * then the modem send via {@link PduSender} ({@code ACCEPTED} or
 * {@code FAILED} with the AT error as cause). Policy and dedupe run upstream
 * of this handler in the chain.
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
            transmissions.report(command, TransmissionResult.Outcome.EXPIRED, null, null, requestedAt);
            return;
        }

        Technique technique;
        try {
            technique = Technique.valueOf(command.technique());
        } catch (IllegalArgumentException e) {
            transmissions.report(command, TransmissionResult.Outcome.FAILED,
                    "unknown technique " + command.technique(), null, requestedAt);
            return;
        }

        String pdu;
        try {
            pdu = technique.generate(digitsOf(command.msisdn()), defaultParams(technique));
        } catch (IllegalArgumentException e) {
            transmissions.report(command, TransmissionResult.Outcome.FAILED,
                    "cannot build PDU: " + e.getMessage(), null, requestedAt);
            return;
        }

        try {
            sender.send(pdu);
            transmissions.report(command, TransmissionResult.Outcome.ACCEPTED, null, pdu, requestedAt);
        } catch (Exception e) {
            LOG.warn("send failed for emission {}: {}", command.emissionId(), e.getMessage());
            transmissions.report(command, TransmissionResult.Outcome.FAILED,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                    pdu, requestedAt);
        }
    }

    @Override
    public void onRejected(String rawPayload, EmissionConsumer.RejectReason reason) {
        LOG.debug("emission payload discarded before execution ({})", reason);
    }

    /** E.164 destination without the leading '+' (the technique generators want bare digits). */
    static String digitsOf(String msisdn) {
        String digits = msisdn == null ? "" : msisdn;
        if (digits.startsWith("+")) {
            digits = digits.substring(1);
        }
        return digits;
    }

    /** v1 commands carry no parameters - techniques run with their defaults. */
    static TechniqueParams defaultParams(Technique technique) {
        return switch (technique) {
            case SILENT_TP0, WAP_PUSH_EMPTY, MMS_NOTIFY_EMPTY -> TechniqueParams.None.INSTANCE;
            case WAP_PUSH_SL, WAP_PUSH_SI ->
                    new TechniqueParams.WapPush(TechniqueParams.WapScheme.NONE);
            case MWI_TOGGLE -> new TechniqueParams.MwiToggle(true, 1);
        };
    }
}
