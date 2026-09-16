package com.hushsign.agent.runtime.kafka;

import com.hushsign.protocol.v1.EmissionCommand;

/**
 * Receives validated {@link EmissionCommand}s, or rejections of payloads that
 * failed the protocol gate (P1-S6). Handlers run on the consumer's polling
 * thread: keep them short, or pause the consumer for long serial work.
 */
public interface EmissionHandler {

    /** A fresh, schema-valid command that passed the freshness gate. */
    void onCommand(EmissionCommand command) throws Exception;

    /**
     * A payload that was discarded: invalid JSON/schema/cross-field rules,
     * or refused by the freshness gate (STALE / CLOCK_SKEW).
     */
    void onRejected(String rawPayload, EmissionConsumer.RejectReason reason);
}
