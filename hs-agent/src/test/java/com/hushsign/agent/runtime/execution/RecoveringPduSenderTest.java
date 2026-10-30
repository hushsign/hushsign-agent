package com.hushsign.agent.runtime.execution;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                    throw new IOException("send failed on ttyUSB1");
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
                    throw new IOException("port gone");
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
        IOException dead = new IOException("send failed on ttyUSB1");
        IOException missing = new IOException("none of the configured ports answered");
        RecoveringPduSender sender = new RecoveringPduSender(
                pdu -> {
                    throw dead;
                },
                () -> {
                    throw missing;
                });

        Exception thrown = assertThrows(IOException.class, () -> sender.send(PDU));
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
                    throw new IOException("first");
                },
                () -> {
                    reopened.incrementAndGet();
                    return pdu -> {
                        throw new IOException("second");
                    };
                });

        IOException thrown = assertThrows(IOException.class, () -> sender.send(PDU));
        assertEquals("second", thrown.getMessage());
        assertTrue(thrown.getSuppressed()[0].getMessage().contains("first"));
        assertEquals(1, reopened.get(), "one reopen and one retry per send, never a spin");
    }

    @Test
    void reportsTheFailureWhenTheReopenedModemRejectsThePdu() {
        RecoveringPduSender sender = new RecoveringPduSender(
                pdu -> {
                    throw new IOException("port gone");
                },
                () -> pdu -> {
                    throw new IllegalStateException("modem rejected the PDU");
                });

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> sender.send(PDU));
        assertEquals("modem rejected the PDU", thrown.getMessage());
        assertEquals(1, sender.recoveries());
    }
}
