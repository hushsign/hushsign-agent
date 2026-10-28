package com.hushsign.agent.runtime.cancel;

import com.hushsign.agent.runtime.kafka.EmissionConsumer;
import com.hushsign.agent.runtime.kafka.EmissionHandler;
import com.hushsign.agent.runtime.transmission.TransmissionRecorder;
import com.hushsign.protocol.v1.EmissionCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P1-S13: drops emissions the platform cancelled before they reached the modem
 * and records the {@code CANCELLED} outcome.
 *
 * <p>Sits directly behind the dedupe decorator: a cancelled emission consumes
 * its claim (it will never run) and produces evidence, so the platform settles
 * it instead of retrying into a cancellation loop. A {@code CANCELLED}
 * transmission is only written when the cancellation is known — a missing
 * cancellation degrades to the normal expiry/policy gates.
 */
public final class CancellingEmissionHandler implements EmissionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(CancellingEmissionHandler.class);

    private final CancellationStore cancellations;
    private final EmissionHandler delegate;
    private final TransmissionRecorder recorder;
    private final Clock clock;

    private final AtomicLong cancelled = new AtomicLong();

    public CancellingEmissionHandler(CancellationStore cancellations, EmissionHandler delegate,
                                     TransmissionRecorder recorder, Clock clock) {
        this.cancellations = Objects.requireNonNull(cancellations, "cancellations");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void onCommand(EmissionCommand command) throws Exception {
        if (!cancellations.isCancelled(command.emissionId())) {
            delegate.onCommand(command);
            return;
        }
        cancelled.incrementAndGet();
        LOG.info("emission {} attempt {} was cancelled - not sending", command.emissionId(), command.attempt());
        recorder.recordAttempt(command, "CANCELLED", "cancelled by the platform before execution", Instant.now(clock));
    }

    @Override
    public void onRejected(String rawPayload, EmissionConsumer.RejectReason reason) {
        delegate.onRejected(rawPayload, reason);
    }

    /** Number of emissions dropped because of a cancellation. */
    public long cancelledCount() {
        return cancelled.get();
    }
}
