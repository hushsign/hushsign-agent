package com.hushsign.agent.runtime.cancel;

/**
 * Raised when a cancellation record cannot be persisted — the agent must not
 * pretend it knows about a cancellation it cannot prove after a restart.
 */
public class CancellationException extends RuntimeException {

    public CancellationException(String message, Throwable cause) {
        super(message, cause);
    }
}
