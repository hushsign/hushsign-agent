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

    /**
     * Whether the modem in use claims to be fit to send. Cheap and local: the state the object
     * holds, not a conversation with the hardware.
     */
    @FunctionalInterface
    public interface Ready {
        boolean isReady();
    }

    /**
     * Asks the hardware. Only called for a modem that claims READY, so a send in progress is
     * never interrupted.
     */
    @FunctionalInterface
    public interface Probe {
        boolean responds();
    }

    private final Ready ready;
    private final Probe probe;

    public RecoveringPduSender(Channel initial, Reopen reopen, Ready ready, Probe probe) {
        this.channel = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
        this.reopen = Objects.requireNonNull(reopen, "reopen");
        this.ready = Objects.requireNonNull(ready, "ready");
        this.probe = Objects.requireNonNull(probe, "probe");
    }

    /** A sender with no health check of its own: it only recovers when a send fails. */
    public RecoveringPduSender(Channel initial, Reopen reopen) {
        this(initial, reopen, () -> true, () -> true);
    }

    @Override
    public synchronized int send(String pduHex) throws ModemException {
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

    /**
     * Checks the modem and puts it back into service if it has gone.
     *
     * <p><b>Why this lives here and is synchronized.</b> A reopen replaces the {@code Modem}, and
     * with it the lock that serialises access to the port - so a recovery running beside a send
     * drives the same serial device through two handles. On 2026-11-02 a thread dump caught
     * exactly that: the consumer inside the driver's write while the watchdog was inside its
     * response reader, both talking to one dongle, and the send stalled until the two AT
     * conversations had each timed out. Holding one lock on the sender, which outlives every
     * modem it opens, makes "one port, one conversation" true again: a send in progress delays
     * the check, and a reopen delays the next send, which is what a single serial line means.
     *
     * @return true when this call put a broken modem back into service
     */
    public synchronized boolean recoverIfUnhealthy() {
        boolean claimsReady = ready.isReady();
        if (claimsReady && probe.responds()) {
            return false;
        }
        if (claimsReady) {
            LOG.warn("the modem claims READY but did not answer, reopening it");
        }
        Channel current = channel.get();
        try {
            Channel replacement = reopen.open();
            channel.set(replacement);
            recoveries.incrementAndGet();
            LOG.info("modem reopened and it is usable again");
            return true;
        } catch (ModemException e) {
            // Expected while a dongle is mid-re-enumeration: the next tick tries again.
            LOG.warn("the modem is not ready and could not be reopened: {}", e.getMessage());
            return false;
        }
    }

    /** How many times the port has been reopened; reported by tests and the admin surface. */
    public int recoveries() {
        return recoveries.get();
    }
}
