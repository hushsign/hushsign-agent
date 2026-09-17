package com.hushsign.agent.runtime.spool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        int flushed = spool.flushTo(record -> {
            if (attempts.incrementAndGet() == 2) {
                throw new IOException("broker down on the second record");
            }
            sent.add(record.key());
        });

        assertEquals(1, flushed, "the flush stops at the first failure");
        assertEquals(List.of("k1"), sent);
        assertEquals(2, spool.size(), "failed record and newer ones stay spooled");
        assertEquals(List.of("k2", "k3"), spool.peekAll().stream().map(SpooledRecord::key).toList());

        // broker back: the rest flushes in order
        int remaining = spool.flushTo(record -> sent.add(record.key()));
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
        assertEquals(0, spool.flushTo(record -> {
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
}
