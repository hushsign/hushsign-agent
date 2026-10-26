package com.hushsign.agent.runtime.cancel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-S13: a cancellation must survive a restart (it is the primitive behind
 * "nothing is sent after a warrant is revoked") and stay inside its window.
 */
class CancellationStoreTest {

    private static final String ID = "5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e";

    @TempDir
    Path tempDir;

    @Test
    void cancelledEmissionsAreRememberedAcrossInstances() {
        Path file = tempDir.resolve("cancellations.ids");
        CancellationStore.open(file, 100).cancel(ID);

        assertTrue(Files.exists(file));
        assertTrue(CancellationStore.open(file, 100).isCancelled(ID), "a restart must keep the cancellation");
    }

    @Test
    void recordingTwiceKeepsOneEntry() {
        CancellationStore store = CancellationStore.open(tempDir.resolve("cancellations.ids"), 100);
        store.cancel(ID);
        store.cancel(ID);

        assertEquals(1, store.size());
        assertTrue(store.isCancelled(ID));
    }

    @Test
    void unknownEmissionsAreNotCancelled() {
        CancellationStore store = CancellationStore.open(tempDir.resolve("cancellations.ids"), 100);
        assertFalse(store.isCancelled(ID));
        assertFalse(store.isCancelled(null));
    }

    @Test
    void theWindowIsBoundedAndEvictsTheOldest() {
        Path file = tempDir.resolve("cancellations.ids");
        CancellationStore store = CancellationStore.open(file, 2);
        store.cancel("a");
        store.cancel("b");
        store.cancel("c");

        assertEquals(2, store.size());
        assertFalse(store.isCancelled("a"));
        assertTrue(store.isCancelled("b"));
        assertTrue(store.isCancelled("c"));
        assertEquals(2, CancellationStore.open(file, 2).size(), "the file must mirror the window");
    }
}
