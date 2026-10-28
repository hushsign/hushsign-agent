package com.hushsign.agent.runtime.kafka;

import com.hushsign.agent.runtime.dedupe.DedupeStore;
import com.hushsign.agent.runtime.execution.EmissionExecutionException;
import com.hushsign.protocol.v1.EmissionCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * P1-S7: drops redelivered emissions before they reach the execution pipeline.
 *
 * <p>The claim key is {@code emissionId:attempt}, never the bare id: a retry
 * (P4-S6) keeps the {@code emissionId} and only bumps {@code attempt}, and it
 * must execute.
 *
 * <p>Claim ordering is fail-closed in both directions:
 * <ul>
 *   <li>the claim is persisted <em>before</em> execution, so a crash mid-send
 *       cannot let a redelivery double-ping the target;</li>
 *   <li>when the attempt fails before the modem was touched
 *       ({@link EmissionExecutionException#sent()} false, or any unexpected
 *       error), the claim is released and the batch stays uncommitted — the
 *       redelivery retries instead of the emission being silently lost.</li>
 * </ul>
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

    /** The persisted claim key of one attempt: retries of the same emission are distinct. */
    public static String claimKey(EmissionCommand command) {
        return command.emissionId() + ":" + command.attempt();
    }

    @Override
    public void onCommand(EmissionCommand command) throws Exception {
        String key = claimKey(command);
        if (!store.tryClaim(key)) {
            duplicates.incrementAndGet();
            LOG.info("dropping duplicate emission {} attempt {}", command.emissionId(), command.attempt());
            return;
        }
        try {
            delegate.onCommand(command);
        } catch (EmissionExecutionException e) {
            if (e.sent()) {
                // The PDU may have left: keep the claim so a redelivery cannot re-send.
                LOG.error("emission {} attempt {} was sent but could not be reported; keeping the claim",
                        command.emissionId(), command.attempt(), e);
                throw e;
            }
            release(key, command, e);
            throw e;
        } catch (RuntimeException | Error e) {
            release(key, command, e);
            throw e;
        }
    }

    @Override
    public void onRejected(String rawPayload, EmissionConsumer.RejectReason reason) {
        delegate.onRejected(rawPayload, reason);
    }

    public long duplicateCount() {
        return duplicates.get();
    }

    private void release(String key, EmissionCommand command, Throwable cause) {
        try {
            if (store.release(key) && LOG.isWarnEnabled()) {
                LOG.warn("released dedupe claim for emission {} attempt {} after {}",
                        command.emissionId(), command.attempt(), cause.toString());
            }
        } catch (RuntimeException e) {
            // Keep the original failure as the cause; a claim left in place is the safe outcome.
            LOG.error("cannot release dedupe claim {}", key, e);
        }
    }
}
