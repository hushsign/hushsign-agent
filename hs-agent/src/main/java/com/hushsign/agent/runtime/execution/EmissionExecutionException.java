package com.hushsign.agent.runtime.execution;

/**
 * P1-S7/P1-S13: an attempt could not be turned into evidence (spool/disk/broker
 * failure while reporting). The consumer must not commit the batch, so
 * redelivery can retry — but whether a retry is safe depends on {@link #sent()}:
 * once the modem was touched, re-sending would double-ping the target.
 */
public final class EmissionExecutionException extends RuntimeException {

    private final boolean sent;

    public EmissionExecutionException(boolean sent, String message, Throwable cause) {
        super(message, cause);
        this.sent = sent;
    }

    /** {@code true} when the PDU already left for the modem — a retry must not re-send. */
    public boolean sent() {
        return sent;
    }
}
