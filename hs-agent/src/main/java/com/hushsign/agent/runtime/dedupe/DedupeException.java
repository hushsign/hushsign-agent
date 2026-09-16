package com.hushsign.agent.runtime.dedupe;

/**
 * Unchecked failure of the dedupe store: when a claim cannot be persisted the
 * agent must fail closed — a send whose uniqueness cannot be guaranteed is
 * never attempted.
 */
public class DedupeException extends RuntimeException {

    public DedupeException(String message, Throwable cause) {
        super(message, cause);
    }
}
