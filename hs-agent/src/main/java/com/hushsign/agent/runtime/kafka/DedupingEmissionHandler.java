package com.hushsign.agent.runtime.kafka;

import com.hushsign.agent.runtime.dedupe.DedupeStore;
import com.hushsign.protocol.v1.EmissionCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * P1-S7: drops redelivered emissions before they reach the execution pipeline.
 * Wraps the P1-S6 {@link EmissionHandler} with a {@link DedupeStore} claim on
 * {@code emissionId} — exactly-once dispatch per emission across restarts.
 */
public final class DedupingEmissionHandler implements EmissionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(DedupingEmissionHandler.class);

    private final DedupeStore store;
    private final EmissionHandler delegate;
    private final AtomicLong duplicates = new AtomicLong();

    public DedupingEmissionHandler(DedupeStore store, EmissionHandler delegate) {
        this.store = store;
        this.delegate = delegate;
    }

    @Override
    public void onCommand(EmissionCommand command) throws Exception {
        if (store.tryClaim(command.emissionId())) {
            delegate.onCommand(command);
        } else {
            duplicates.incrementAndGet();
            LOG.info("dropping duplicate emission {}", command.emissionId());
        }
    }

    @Override
    public void onRejected(String rawPayload, EmissionConsumer.RejectReason reason) {
        delegate.onRejected(rawPayload, reason);
    }

    public long duplicateCount() {
        return duplicates.get();
    }
}
