package com.hushsign.agent.runtime.execution;

import com.hushsign.agent.gsm.modem.ModemException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Puts a modem that stopped working back into service without waiting for traffic.
 *
 * <p><b>Why a watchdog and not just the retry in {@link RecoveringPduSender}.</b> That retry only
 * runs when a command actually reaches the modem, and the policy gate sits <em>before</em> the
 * executor: a gateway whose target is over its hourly cap, or inside quiet hours, or whose
 * schedule was stopped, receives nothing that may be sent - so nothing ever touches the port.
 * On 2026-11-02 this workstation's dongle re-enumerated mid-burst at 16:40:26, the reopen attempt
 * found no port (the device was still resetting), and the agent then sat at DEGRADED reporting a
 * broken modem for hours while the device was back on the bus within 20 seconds and every
 * subsequent command was refused by the per-target cap before the modem was consulted.
 *
 * <p>So recovery is its own reason to touch the port, on its own clock:
 *
 * <ul>
 *   <li>a modem that says it is READY is asked once whether it is still there, because nothing
 *       else touches the port when no command is being sent: after a re-enumeration the old
 *       handle keeps claiming READY about a device node that no longer exists, and the heartbeat
 *       then reports a healthy modem that cannot send;</li>
 *   <li>a modem that is DEGRADED, ERROR or otherwise not ready gets one reopen attempt per tick,
 *       which is also the probe: a reopen runs the full init, so success means a working modem
 *       rather than a port that merely opened;</li>
 *   <li>a failed reopen is logged and forgotten until the next tick, never retried in a loop.
 *       A device that is genuinely gone must not spin the serial port.</li>
 * </ul>
 */
public final class ModemWatchdog {

    private static final Logger LOG = LoggerFactory.getLogger(ModemWatchdog.class);

    /**
     * Whether the modem in use claims to be fit to send. Cheap and local: this is the state the
     * object holds, not a conversation with the hardware.
     */
    @FunctionalInterface
    public interface Ready {
        boolean isReady();
    }

    /**
     * Asks the hardware. Only called for a modem that claims READY, so a BUSY port is never
     * interrupted mid-send.
     */
    @FunctionalInterface
    public interface Probe {
        boolean responds();
    }

    /** Claims the modem again, replacing whatever the runtime is holding. */
    @FunctionalInterface
    public interface Recovery {
        void reopen() throws ModemException;
    }

    private final Ready ready;
    private final Probe probe;
    private final Recovery recovery;

    /** A watchdog that trusts the reported state - for callers with no port to check. */
    public ModemWatchdog(Ready ready, Recovery recovery) {
        this(ready, () -> true, recovery);
    }

    public ModemWatchdog(Ready ready, Probe probe, Recovery recovery) {
        this.ready = Objects.requireNonNull(ready, "ready");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.recovery = Objects.requireNonNull(recovery, "recovery");
    }

    /**
     * One check.
     *
     * @return true when this call put a broken modem back into service
     */
    public boolean checkOnce() {
        boolean claimsReady = ready.isReady();
        if (claimsReady && probe.responds()) {
            return false;
        }
        if (claimsReady) {
            LOG.warn("modem watchdog: the modem claims READY but did not answer, reopening it");
        }
        try {
            recovery.reopen();
            LOG.info("modem watchdog: reopened the modem and it is usable again");
            return true;
        } catch (ModemException e) {
            // Expected while a dongle is mid-re-enumeration: the next tick tries again.
            LOG.warn("modem watchdog: the modem is not ready and could not be reopened: {}", e.getMessage());
            return false;
        }
    }
}
