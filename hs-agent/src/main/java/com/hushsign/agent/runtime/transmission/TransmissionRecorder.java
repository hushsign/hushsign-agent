package com.hushsign.agent.runtime.transmission;

import com.hushsign.protocol.v1.EmissionCommand;

import java.time.Instant;

/**
 * Minimal seam for "this attempt produced an outcome, record it" — implemented by
 * {@link TransmissionProducer} (spooled to {@code hs.transmissions.v1}).
 *
 * <p>Lets the gates in front of the modem (policy, freshness) report their
 * refusals as real transmissions instead of only counting them, without
 * depending on the spool wiring.
 */
public interface TransmissionRecorder {

    /**
     * Records an attempt that did not produce (or did not need) a PDU.
     *
     * @param command     the emission this outcome belongs to
     * @param outcome     a {@code TransmissionResult.Outcome} constant
     * @param failureCause reason for the refusal, or {@code null}
     * @param requestedAt when the agent decided on the attempt
     */
    void recordAttempt(EmissionCommand command, String outcome, String failureCause, Instant requestedAt);
}
