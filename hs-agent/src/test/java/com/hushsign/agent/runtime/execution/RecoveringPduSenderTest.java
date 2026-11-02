package com.hushsign.agent.runtime.execution;

import org.junit.jupiter.api.Test;

import com.hushsign.agent.gsm.modem.ModemException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A dongle that re-enumerates must not take the gateway down with it: one failed send reopens the
 * modem and retries, and a modem that cannot be reopened reports why.
 */
class RecoveringPduSenderTest {

    private static final String PDU = "0011000B9140";

    @Test
    void sendsOnTheOpenPortWithoutReopening() throws Exception {
        List<String> opened = new ArrayList<>();
        RecoveringPduSender sender = new RecoveringPduSender(
                pdu -> 7,
                () -> {
                    opened.add("open");
                    return pdu -> 8;
                });

        assertEquals(7, sender.send(PDU));
        assertEquals(List.of(), opened);
        assertEquals(0, sender.recoveries());
    }

    @Test
    void reopensTheModemAndRetriesTheSamePduWhenThePortDied() throws Exception {
        AtomicInteger reopened = new AtomicInteger();
        List<String> retried = new ArrayList<>();
        RecoveringPduSender sender = new RecoveringPduSender(
                pdu -> {
                    throw new ModemException("send failed on ttyUSB1");
                },
                () -> {
                    reopened.incrementAndGet();
                    return pdu -> {
                        retried.add(pdu);
                        return 42;
                    };
                });

        assertEquals(42, sender.send(PDU), "the retry on the reopened modem decides the result");
        assertEquals(1, reopened.get());
        assertEquals(List.of(PDU), retried, "the retry must carry the same PDU, not a rebuilt one");
        assertEquals(1, sender.recoveries());
    }

    @Test
    void keepsUsingTheReopenedModemForLaterSends() throws Exception {
        AtomicInteger reopened = new AtomicInteger();
        RecoveringPduSender sender = new RecoveringPduSender(
                pdu -> {
                    throw new ModemException("port gone");
                },
                () -> {
                    reopened.incrementAndGet();
                    return pdu -> 5;
                });

        assertEquals(5, sender.send(PDU));
        assertEquals(5, sender.send(PDU));
        assertEquals(5, sender.send(PDU));
        assertEquals(1, reopened.get(), "a healthy channel must not be reopened per send");
    }

    @Test
    void reportsBothTheSendAndTheReopenFailureWhenTheModemCannotComeBack() {
        ModemException dead = new ModemException("send failed on ttyUSB1");
        ModemException missing = new ModemException("none of the configured ports answered");
        RecoveringPduSender sender = new RecoveringPduSender(
                pdu -> {
                    throw dead;
                },
                () -> {
                    throw missing;
                });

        ModemException thrown = assertThrows(ModemException.class, () -> sender.send(PDU));
        assertSame(missing, thrown, "the reopen failure is what the operator has to act on");
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(dead, thrown.getSuppressed()[0], "the original send failure is kept as context");
        assertEquals(0, sender.recoveries(), "a failed reopen is not a recovery");
    }

    @Test
    void doesNotLoopWhenTheReopenedModemAlsoFails() {
        AtomicInteger reopened = new AtomicInteger();
        RecoveringPduSender sender = new RecoveringPduSender(
                pdu -> {
                    throw new ModemException("first");
                },
                () -> {
                    reopened.incrementAndGet();
                    return pdu -> {
                        throw new ModemException("second");
                    };
                });

        ModemException thrown = assertThrows(ModemException.class, () -> sender.send(PDU));
        assertEquals("second", thrown.getMessage());
        assertTrue(thrown.getSuppressed()[0].getMessage().contains("first"));
        assertEquals(1, reopened.get(), "one reopen and one retry per send, never a spin");
    }

    @Test
    void reportsTheFailureWhenTheReopenedModemRejectsThePdu() {
        RecoveringPduSender sender = new RecoveringPduSender(
                pdu -> {
                    throw new ModemException("port gone");
                },
                () -> pdu -> {
                    throw new IllegalStateException("modem rejected the PDU");
                });

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> sender.send(PDU));
        assertEquals("modem rejected the PDU", thrown.getMessage());
        assertEquals(1, sender.recoveries());
    }

    // ---- health check: the modem must be asked, not trusted ------------------------------

    private static RecoveringPduSender.Channel ok(int index) {
        return pdu -> index;
    }

    @Test
    void leavesAHealthyModemAlone() {
        AtomicInteger reopened = new AtomicInteger();
        RecoveringPduSender sender = new RecoveringPduSender(ok(1),
                () -> {
                    reopened.incrementAndGet();
                    return ok(2);
                },
                () -> true,
                () -> true);

        assertFalse(sender.recoverIfUnhealthy());
        assertEquals(0, reopened.get(), "a modem that answers must not be reopened - that re-runs the init");
    }

    @Test
    void reopensAModemThatClaimsReadyButDoesNotAnswer() {
        AtomicInteger reopened = new AtomicInteger();
        RecoveringPduSender sender = new RecoveringPduSender(ok(1),
                () -> {
                    reopened.incrementAndGet();
                    return ok(2);
                },
                () -> true,
                () -> false);

        assertTrue(sender.recoverIfUnhealthy());
        assertEquals(1, reopened.get());
        assertEquals(1, sender.recoveries());
    }

    @Test
    void doesNotAskThePortWhenTheModemIsNotReady() {
        AtomicInteger probes = new AtomicInteger();
        AtomicInteger reopened = new AtomicInteger();
        RecoveringPduSender sender = new RecoveringPduSender(ok(1),
                () -> {
                    reopened.incrementAndGet();
                    return ok(2);
                },
                () -> false,
                () -> {
                    probes.incrementAndGet();
                    return true;
                });

        assertTrue(sender.recoverIfUnhealthy());
        assertEquals(0, probes.get(), "a DEGRADED modem goes straight to recovery");
        assertEquals(1, reopened.get());
    }

    @Test
    void reportsAFailedReopenAndTriesAgainOnTheNextTick() {
        RecoveringPduSender sender = new RecoveringPduSender(ok(1),
                () -> {
                    throw new ModemException("none of the configured ports answered");
                },
                () -> false,
                () -> true);

        assertFalse(sender.recoverIfUnhealthy());
        assertFalse(sender.recoverIfUnhealthy(), "a device that is gone must not spin the port");
        assertEquals(0, sender.recoveries(), "a failed reopen is not a recovery");
    }

    @Test
    void aHealthCheckCannotRunBesideASend() throws Exception {
        // One serial line, one conversation. Before this lock existed, the send path held the
        // Modem it started with while a recovery replaced it, so the consumer could be writing to
        // the dongle while the check was reading a reply from it - measured on 2026-11-02, and it
        // stalled the only consumer until both AT conversations timed out.
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        AtomicInteger reopened = new AtomicInteger();
        AtomicBoolean overlapped = new AtomicBoolean(false);
        AtomicBoolean sendInProgress = new AtomicBoolean(false);

        RecoveringPduSender sender = new RecoveringPduSender(
                pdu -> {
                    sendInProgress.set(true);
                    sendStarted.countDown();
                    try {
                        releaseSend.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    sendInProgress.set(false);
                    return 1;
                },
                () -> {
                    if (sendInProgress.get()) {
                        overlapped.set(true);
                    }
                    reopened.incrementAndGet();
                    return pdu -> 2;
                },
                () -> false,
                () -> true);

        Thread sending = new Thread(() -> {
            try {
                sender.send(PDU);
            } catch (ModemException _) {
                // not the subject of this test
            }
        });
        sending.start();
        assertTrue(sendStarted.await(5, TimeUnit.SECONDS), "the send must start");

        Thread checking = new Thread(sender::recoverIfUnhealthy);
        checking.start();
        Thread.sleep(300);

        assertTrue(checking.isAlive(), "the check must wait for the send, not run beside it");
        assertFalse(overlapped.get(), "a reopen must never happen while a send is using the port");

        releaseSend.countDown();
        sending.join(5_000);
        checking.join(5_000);
        assertEquals(1, reopened.get(), "and it does run once the port is free");
    }
}
