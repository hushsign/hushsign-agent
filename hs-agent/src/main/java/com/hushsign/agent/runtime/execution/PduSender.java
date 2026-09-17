package com.hushsign.agent.runtime.execution;

/**
 * Sends one SMS-SUBMIT PDU hex string to a modem (P1-S13).
 *
 * <p>Wrapped as a seam so the execution pipeline stays testable without a
 * modem: production wiring adapts {@code modem::sendPdu}, tests use a lambda
 * or a failing stub. Implementations throw any exception on failure — the
 * executor maps that to a {@code FAILED} transmission, never a retry inside
 * the pipeline.
 */
@FunctionalInterface
public interface PduSender {

    /**
     * @param pduHex uppercase SMS-SUBMIT PDU hex string
     * @return the modem's message index on success
     * @throws Exception on any send failure (AT error, timeout, port closed)
     */
    int send(String pduHex) throws Exception;
}
