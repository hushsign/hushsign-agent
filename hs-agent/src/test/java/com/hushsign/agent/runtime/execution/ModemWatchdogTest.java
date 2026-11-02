package com.hushsign.agent.runtime.execution;

import com.hushsign.agent.gsm.modem.ModemException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A modem that died while nothing was being sent must come back without waiting for traffic: the
 * policy gate sits in front of the executor, so a capped or idle gateway would otherwise report a
 * broken modem until somebody restarted the service.
 */
class ModemWatchdogTest {

    @Test
    void leavesAHealthyModemAlone() {
        AtomicInteger reopened = new AtomicInteger();
        ModemWatchdog watchdog = new ModemWatchdog(() -> true, reopened::incrementAndGet);

        assertFalse(watchdog.checkOnce());
        assertEquals(0, reopened.get(), "a READY modem must not be reopened - that re-runs the init");
    }

    @Test
    void reopensAModemThatIsNotReady() {
        AtomicInteger reopened = new AtomicInteger();
        ModemWatchdog watchdog = new ModemWatchdog(() -> false, reopened::incrementAndGet);

        assertTrue(watchdog.checkOnce());
        assertEquals(1, reopened.get());
    }

    @Test
    void reportsAFailedReopenAndKeepsGoing() {
        ModemWatchdog watchdog = new ModemWatchdog(() -> false, () -> {
            throw new ModemException("none of the configured ports answered");
        });

        // The dongle is mid-re-enumeration: this must be a logged miss, not an exception that
        // kills the watchdog thread, and not a spin - the next tick is the retry.
        assertFalse(watchdog.checkOnce());
        assertFalse(watchdog.checkOnce());
    }

    @Test
    void reopensAModemThatClaimsReadyButDoesNotAnswer() {
        // The failure that made the heartbeat lie: after a re-enumeration the old handle still
        // says READY about a device node that is gone, so nothing looks at it - and the platform
        // is told a healthy modem cannot send.
        AtomicInteger reopened = new AtomicInteger();
        ModemWatchdog watchdog = new ModemWatchdog(() -> true, () -> false, reopened::incrementAndGet);

        assertTrue(watchdog.checkOnce());
        assertEquals(1, reopened.get());
    }

    @Test
    void doesNotAskThePortWhenTheModemIsNotReady() {
        AtomicInteger probes = new AtomicInteger();
        AtomicInteger reopened = new AtomicInteger();
        ModemWatchdog watchdog = new ModemWatchdog(() -> false, () -> {
            probes.incrementAndGet();
            return true;
        }, reopened::incrementAndGet);

        assertTrue(watchdog.checkOnce());
        assertEquals(0, probes.get(), "a DEGRADED modem goes straight to recovery");
        assertEquals(1, reopened.get());
    }

    @Test
    void leavesAReadyModemThatAnswersAlone() {
        AtomicInteger reopened = new AtomicInteger();
        ModemWatchdog watchdog = new ModemWatchdog(() -> true, () -> true, reopened::incrementAndGet);

        assertFalse(watchdog.checkOnce());
        assertEquals(0, reopened.get());
    }

    @Test
    void stopsTryingOnceTheModemIsBack() {
        AtomicInteger reopened = new AtomicInteger();
        boolean[] ready = {false};
        ModemWatchdog watchdog = new ModemWatchdog(() -> ready[0], () -> {
            reopened.incrementAndGet();
            ready[0] = true;
        });

        assertTrue(watchdog.checkOnce());
        assertFalse(watchdog.checkOnce());
        assertFalse(watchdog.checkOnce());
        assertEquals(1, reopened.get(), "recovery happens once, not once per tick");
    }
}
