package com.hushsign.agent.runtime.spool;

/**
 * Unchecked failure of the spool: a record that cannot be persisted (or an
 * ack that cannot be committed) must surface — silently losing outbound
 * evidence would break the agent's auditability.
 */
public class SpoolException extends RuntimeException {

    public SpoolException(String message, Throwable cause) {
        super(message, cause);
    }
}
