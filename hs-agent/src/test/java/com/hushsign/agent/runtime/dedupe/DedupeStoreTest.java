package com.hushsign.agent.runtime.dedupe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-S7 tests: exactly-once claims, persistence across restarts, bounded
 * window with eviction, fail-closed IO behaviour.
 */
class DedupeStoreTest {

    @TempDir
    Path tempDir;

    private Path file() {
        return tempDir.resolve("dedupe.ids");
    }

    @Test
    void firstClaimWinsAndRedeliveriesAreRejected() {
        DedupeStore store = DedupeStore.open(file(), 100);

        assertTrue(store.tryClaim("5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e"));
        assertFalse(store.tryClaim("5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e"),
                "redelivery of the same emissionId must be dropped");
        assertTrue(store.tryClaim("5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6f"));
        assertEquals(2, store.size());
    }

    @Test
    void claimsPersistAcrossRestarts() throws IOException {
        DedupeStore first = DedupeStore.open(file(), 100);
        assertTrue(first.tryClaim("5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e"));
        assertTrue(first.tryClaim("b4c1a2d3-84f5-4a4c-8c0f-112233445566"));

        DedupeStore reopened = DedupeStore.open(file(), 100);
        assertFalse(reopened.tryClaim("5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e"),
                "persisted ids must survive a restart");
        assertFalse(reopened.tryClaim("b4c1a2d3-84f5-4a4c-8c0f-112233445566"));
        assertTrue(reopened.tryClaim("11223344-5566-7788-99aa-bbccddeeff00"));
        assertEquals(3, reopened.size());
        assertTrue(Files.readAllLines(file()).contains("11223344-5566-7788-99aa-bbccddeeff00"));
    }

    @Test
    void windowEvictsOldestClaims() {
        String id1 = "aaaaaaaa-0000-0000-0000-000000000001";
        String id2 = "aaaaaaaa-0000-0000-0000-000000000002";
        String id3 = "aaaaaaaa-0000-0000-0000-000000000003";
        String id4 = "aaaaaaaa-0000-0000-0000-000000000004";
        DedupeStore store = DedupeStore.open(file(), 3);

        // window {1, 2, 3}
        assertTrue(store.tryClaim(id1));
        assertTrue(store.tryClaim(id2));
        assertTrue(store.tryClaim(id3));

        // claim 4 evicts 1 -> {2, 3, 4}
        assertTrue(store.tryClaim(id4));
        assertEquals(3, store.size());
        assertTrue(store.tryClaim(id1), "evicted ids may be claimed again");
        assertEquals(3, store.size());

        // re-claiming 1 evicted 2 -> {3, 4, 1}; claiming 2 evicts 3 -> {4, 1, 2};
        // claiming 3 evicts 4 -> {1, 2, 3}
        assertTrue(store.tryClaim(id2), "id2 left the window when id1 was re-claimed");
        assertEquals(3, store.size());
        assertTrue(store.tryClaim(id3), "id3 left the window when id2 was re-claimed");
        assertEquals(Set.of(id1, id2, id3), store.snapshot(), "claiming id3 evicted id4");

        // the file mirrors the in-memory window after evictions: {1, 2, 3}
        DedupeStore reopened = DedupeStore.open(file(), 3);
        assertEquals(Set.of(id1, id2, id3), reopened.snapshot(), "disk matches the window");
        assertTrue(reopened.tryClaim(id4), "the evicted id must be gone from disk too");
    }

    @Test
    void missingFileStartsEmpty() {
        DedupeStore store = DedupeStore.open(tempDir.resolve("nested/missing.ids"), 10);
        assertEquals(0, store.size());
        assertTrue(store.tryClaim("5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e"));
        assertEquals(1, store.size());
    }

    @Test
    void blankLinesAreSkippedOnLoad() throws IOException {
        Path file = file();
        Files.writeString(file, "\n   \n5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e\nb4c1a2d3-84f5-4a4c-8c0f-112233445566\n");

        DedupeStore store = DedupeStore.open(file, 10);
        assertFalse(store.tryClaim("5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e"));
        assertFalse(store.tryClaim("b4c1a2d3-84f5-4a4c-8c0f-112233445566"));
        assertEquals(2, store.size(), "ids are opaque tokens: only blank lines are skipped");
    }

    @Test
    void blankClaimsAreRejected() {
        DedupeStore store = DedupeStore.open(file(), 10);
        assertThrows(IllegalArgumentException.class, () -> store.tryClaim("  "));
        assertThrows(IllegalArgumentException.class, () -> store.tryClaim(null));
    }

    @Test
    void concurrentClaimsOfTheSameIdSucceedExactlyOnce() throws Exception {
        DedupeStore store = DedupeStore.open(file(), 100);
        String id = "5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e";
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                tasks.add(() -> store.tryClaim(id));
            }
            List<Future<Boolean>> results = pool.invokeAll(tasks);
            long wins = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    wins++;
                }
            }
            assertEquals(1, wins, "exactly one concurrent claimant may win");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void unwritableFileFailsClosedAndRollsBackTheClaim() throws IOException {
        Path file = file();
        DedupeStore store = DedupeStore.open(file, 10);
        assertTrue(store.tryClaim("aaaaaaaa-0000-0000-0000-000000000001"));

        // replace the backing file with a directory: the next append must fail
        Files.delete(file);
        Files.createDirectories(file);

        assertThrows(DedupeException.class,
                () -> store.tryClaim("aaaaaaaa-0000-0000-0000-000000000002"));
        assertEquals(1, store.size(), "the failed claim must be rolled back");
        assertFalse(store.tryClaim("aaaaaaaa-0000-0000-0000-000000000001"),
                "the surviving window still answers");
    }
}
