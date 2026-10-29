package com.hushsign.agent.runtime.spool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-S8 tests: FIFO ordering, durability across restarts, ordered flush that
 * stops at the first failure, and fail-closed IO behaviour.
 */
class SpoolTest {

    @TempDir
    Path tempDir;

    private Path file() {
        return tempDir.resolve("spool.jsonl");
    }

    private static void enqueue(Spool spool, String topic, String key, String value) {
        spool.enqueue(topic, key, value);
    }

    @Test
    void enqueueAppendsAndPeekReturnsOldestFirst() {
        Spool spool = Spool.open(file());
        enqueue(spool, "hs.transmissions.v1", "em-1", "{\"outcome\":\"ACCEPTED\"}");
        enqueue(spool, "hs.fleet.heartbeat.v1", "gw-1", "{\"gatewayId\":\"gw-1\"}");
        enqueue(spool, "hs.audit.digest.v1", "gw-1", "{\"chainHead\":\"ab\"}");

        assertEquals(3, spool.size());
        assertEquals(List.of("em-1", "gw-1", "gw-1"),
                spool.peekAll().stream().map(SpooledRecord::key).toList());
        assertEquals("hs.transmissions.v1", spool.peekAll().get(0).topic());
    }

    @Test
    void spoolSurvivesRestartsInOrder() {
        Spool first = Spool.open(file());
        enqueue(first, "hs.transmissions.v1", "em-1", "{\"a\":1}");
        enqueue(first, "hs.transmissions.v1", "em-2", "{\"a\":2}");

        Spool reopened = Spool.open(file());
        assertEquals(List.of("em-1", "em-2"),
                reopened.peekAll().stream().map(SpooledRecord::key).toList());
        assertEquals("{\"a\":1}", reopened.peekAll().get(0).value());
    }

    @Test
    void flushStopsAtTheFirstFailureAndKeepsOrder() {
        Spool spool = Spool.open(file());
        enqueue(spool, "t", "k1", "{\"n\":1}");
        enqueue(spool, "t", "k2", "{\"n\":2}");
        enqueue(spool, "t", "k3", "{\"n\":3}");

        List<String> sent = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        int flushed = spool.flushTo(entry -> {
            if (attempts.incrementAndGet() == 2) {
                throw new IOException("broker down on the second record");
            }
            sent.add(entry.key());
        });

        assertEquals(1, flushed, "the flush stops at the first failure");
        assertEquals(List.of("k1"), sent);
        assertEquals(2, spool.size(), "failed record and newer ones stay spooled");
        assertEquals(List.of("k2", "k3"), spool.peekAll().stream().map(SpooledRecord::key).toList());

        // broker back: the rest flushes in order
        int remaining = spool.flushTo(entry -> sent.add(entry.key()));
        assertEquals(2, remaining);
        assertEquals(List.of("k1", "k2", "k3"), sent);
        assertEquals(0, spool.size());
        assertTrue(spool.peekAll().isEmpty());
    }

    @Test
    void acksArePersistedAcrossRestarts() {
        Spool spool = Spool.open(file());
        enqueue(spool, "t", "k1", "{\"n\":1}");
        enqueue(spool, "t", "k2", "{\"n\":2}");
        enqueue(spool, "t", "k3", "{\"n\":3}");

        spool.ackFirst(2);
        assertEquals(1, spool.size());

        Spool reopened = Spool.open(file());
        assertEquals(List.of("k3"), reopened.peekAll().stream().map(SpooledRecord::key).toList(),
                "acks must be visible after a restart");
    }

    @Test
    void emptySpoolFlushesNothing() {
        Spool spool = Spool.open(file());
        assertEquals(0, spool.flushTo(entry -> {
            throw new AssertionError("nothing to send");
        }));
    }

    @Test
    void invalidAcksAreRejected() {
        Spool spool = Spool.open(file());
        enqueue(spool, "t", "k1", "{\"n\":1}");
        assertThrows(IllegalArgumentException.class, () -> spool.ackFirst(2));
        assertThrows(IllegalArgumentException.class, () -> spool.ackFirst(-1));
        assertEquals(1, spool.size(), "nothing may be lost by a bad ack");
    }

    @Test
    void blankRecordsAreRejected() {
        Spool spool = Spool.open(file());
        assertThrows(IllegalArgumentException.class, () -> spool.enqueue(" ", "k", "{\"n\":1}"));
        assertThrows(IllegalArgumentException.class, () -> spool.enqueue("t", "k", " "));
    }

    @Test
    void unwritableFileFailsClosedAndRollsBackTheRecord() throws IOException {
        Path file = file();
        Spool spool = Spool.open(file);
        enqueue(spool, "t", "k1", "{\"n\":1}");

        Files.delete(file);
        Files.createDirectories(file); // a directory cannot be appended to

        assertThrows(SpoolException.class, () -> spool.enqueue("t", "k2", "{\"n\":2}"));
        assertEquals(1, spool.size(), "the failed record must be rolled back");
    }

    @Test
    void tornTailLinesAreSkippedOnLoad() throws IOException {
        Path file = file();
        Files.writeString(file, """
                {"topic":"t","key":"k1","value":"{\\"n\\":1}"}
                {"topic":"t","key":"k2","value":"torn
                """);

        Spool spool = Spool.open(file);
        assertEquals(1, spool.size(), "the valid prefix survives a torn tail");
        assertEquals("k1", spool.peekAll().get(0).key());
    }

    @Test
    void theByteCapIsEnforcedAndNothingIsLost() {
        Spool spool = Spool.open(file(), 200);
        enqueue(spool, "t", "k1", "{\"n\":1}");

        String overflowing = "{\"padding\":\"" + "x".repeat(300) + "\"}";
        SpoolException e = assertThrows(SpoolException.class,
                () -> spool.enqueue("t", "k2", overflowing));
        assertTrue(e.getMessage().contains("spool is full"), e.getMessage());
        assertEquals(1, spool.size(), "the rejected record must not be buffered");
        assertEquals(1, Spool.open(file(), 400).size(), "and it must not be on disk either");
    }

    @Test
    void aPermanentlyRejectedRecordIsQuarantinedInsteadOfBlockingTheSpool() {
        Spool spool = Spool.open(file());
        enqueue(spool, "t", "poison", "{\"n\":1}");
        enqueue(spool, "t", "k2", "{\"n\":2}");

        List<String> sent = new ArrayList<>();
        SpoolSender sender = entry -> {
            if ("poison".equals(entry.key())) {
                throw new org.apache.kafka.common.errors.RecordTooLargeException("too large");
            }
            sent.add(entry.key());
        };

        // A non-retriable failure is retried a few times, then moved aside.
        for (int i = 0; i < Spool.QUARANTINE_AFTER - 1; i++) {
            assertEquals(0, spool.flushTo(sender), "attempt " + (i + 1) + " must not quarantine yet");
        }
        assertEquals(0, spool.quarantined());

        // The last attempt quarantines the poison record and drains the healthy one.
        assertEquals(1, spool.flushTo(sender));
        assertEquals(List.of("poison"), readLines(spool.poisonFile()).stream()
                .map(line -> line.contains("poison") ? "poison" : line).toList());
        assertEquals(1, spool.quarantined());
        assertEquals(List.of("k2"), sent);
        assertEquals(0, spool.size());
    }

    @Test
    void aBrokerOutageIsNeverQuarantined() {
        Spool spool = Spool.open(file());
        enqueue(spool, "t", "k1", "{\"n\":1}");

        SpoolSender outage = entry -> {
            throw new java.util.concurrent.TimeoutException("broker unreachable");
        };
        for (int i = 0; i < Spool.QUARANTINE_AFTER * 2; i++) {
            assertEquals(0, spool.flushTo(outage));
        }
        assertEquals(0, spool.quarantined(), "an outage must keep every record");
        assertEquals(1, spool.size());
    }

    @Test
    void retriableKafkaErrorsAreNotPermanent() {
        assertTrue(Spool.isPermanent(new RuntimeException(
                new org.apache.kafka.common.errors.RecordTooLargeException("too large"))));
        assertFalse(Spool.isPermanent(new RuntimeException(
                new org.apache.kafka.common.errors.TimeoutException("timeout"))));
        assertFalse(Spool.isPermanent(new java.io.IOException("connection reset")));
    }

    private static List<String> readLines(Path path) {
        try {
            return Files.readAllLines(path);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aBlockedSendDoesNotFreezeProducersOrReaders() throws Exception {
        // P7-S9 field finding: flushTo used to hold the spool monitor across the Kafka send, so a
        // broker that was down froze enqueue (the emission chain) and size() (the admin API) for
        // as long as the send blocked - exactly when an operator asks what is wrong.
        Spool spool = Spool.open(tempDir.resolve("spool.jsonl"));
        spool.enqueue("hs.transmissions.v1", "k", "{\"first\":1}");
        CountDownLatch inSend = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SpoolSender blocked = entry -> {
            inSend.countDown();
            release.await(10, TimeUnit.SECONDS);
        };

        Thread flusher = new Thread(() -> spool.flushTo(blocked), "test-flusher");
        flusher.setDaemon(true);
        flusher.start();
        assertTrue(inSend.await(5, TimeUnit.SECONDS), "the flush must reach the sender");

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertEquals(1, spool.size()),
                "size() must not wait for the send");
        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> spool.enqueue("hs.transmissions.v1", "k2", "{\"second\":2}"),
                "enqueue must not wait for the send");

        release.countDown();
        flusher.join(5000);
        assertFalse(flusher.isAlive(), "the flush finishes once the send returns");
    }
}
