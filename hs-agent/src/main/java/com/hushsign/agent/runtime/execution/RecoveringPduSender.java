package com.hushsign.agent.runtime.execution;

import com.hushsign.agent.gsm.modem.ModemException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sends PDUs, and when the port has gone away underneath the agent, reopens the modem and tries
 * the same PDU once more.
 *
 * <p><b>Why this exists.</b> A gateway agent claims its modem once at startup and then holds the
 * open descriptor for the life of the process. The USB link to a Huawei-class dongle is not that
 * durable: the device re-enumerates on a hub reset, a replug, or its own reboot, and every byte
 * written to the stale descriptor then fails with an I/O error. Until this class existed the
 * agent kept writing to that dead descriptor until someone restarted the service - the configured
 * {@code /dev/serial/by-id/} path, which survives a replug by design, was resolved only once and
 * never consulted again. A gateway that silently stops transmitting is the worst failure mode
 * this product has, so recovery is automatic and bounded.
 *
 * <p><b>Bounded on purpose.</b> One reopen and one retry per send, never a loop: if the retry
 * fails too, the failure is reported to the platform and the next emission starts the cycle
 * again. A modem that is genuinely broken must not spin the serial port while the radio is
 * mid-registration, and the platform's own freshness limit means a command that could not be sent
 * inside its window has to expire rather than be retried forever.
 *
 * <p>The channel seam keeps this testable: production wraps a {@code Modem}, tests supply a
 * lambda that fails on cue.
 */
public final class RecoveringPduSender implements PduSender {

    private static final Logger LOG = LoggerFactory.getLogger(RecoveringPduSender.class);

    /** What this class needs from a modem: send one PDU. */
    @FunctionalInterface
    public interface Channel {
        int sendPdu(String pduHex) throws ModemException;
    }

    /**
     * Opens a fresh modem. Implementations must release the previous port first: two open
     * descriptors on one dongle is the failure this class is recovering from.
     */
    @FunctionalInterface
    public interface Reopen {
        Channel open() throws ModemException;
    }

    private final Reopen reopen;
    private final AtomicReference<Channel> channel;
    private final AtomicInteger recoveries = new AtomicInteger();

    public RecoveringPduSender(Channel initial, Reopen reopen) {
        this.channel = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
        this.reopen = Objects.requireNonNull(reopen, "reopen");
    }

    @Override
    public int send(String pduHex) throws ModemException {
        Channel current = channel.get();
        try {
            return current.sendPdu(pduHex);
        } catch (Exception failure) {
            Channel replacement = replacementAfter(current, failure);
            try {
                int index = replacement.sendPdu(pduHex);
                LOG.info("send succeeded after reopening the modem ({} recovery/recoveries so far)",
                        recoveries.get());
                return index;
            } catch (Exception retryFailure) {
                // Both causes matter: the first says the port died, the second says the fresh one
                // is not usable either, and only together do they describe the modem.
                retryFailure.addSuppressed(failure);
                throw retryFailure;
            }
        }
    }

    /**
     * The channel to retry on, reopening at most once even when several sends fail at the same
     * moment: the emission consumer is single-threaded per operator, but the probe path and a
     * future parallel caller must not open two ports.
     */
    private synchronized Channel replacementAfter(Channel failed, Exception failure) throws ModemException {
        Channel current = channel.get();
        if (current != failed) {
            // Another caller already replaced the dead channel while this one was failing.
            return current;
        }
        try {
            Channel replacement = reopen.open();
            channel.set(replacement);
            recoveries.incrementAndGet();
            LOG.warn("reopened the modem after a failed send: {}", failure.getMessage());
            return replacement;
        } catch (ModemException reopenFailure) {
            reopenFailure.addSuppressed(failure);
            throw reopenFailure;
        }
    }

    /** How many times the port has been reopened; reported by tests and the admin surface. */
    public int recoveries() {
        return recoveries.get();
    }
}
