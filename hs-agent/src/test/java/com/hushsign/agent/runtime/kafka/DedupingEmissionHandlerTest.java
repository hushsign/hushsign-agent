package com.hushsign.agent.runtime.kafka;

import com.hushsign.agent.runtime.dedupe.DedupeStore;
import com.hushsign.agent.runtime.execution.EmissionExecutionException;
import com.hushsign.protocol.v1.EmissionCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * P1-S7 tests: the dedupe decorator forwards each attempt exactly once, drops
 * redeliveries, lets retries through, and never loses an emission whose
 * attempt failed before the modem was touched.
 */
class DedupingEmissionHandlerTest {

    private static final String ID = "5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e";

    @TempDir
    Path tempDir;

    private static EmissionCommand command(String emissionId, int attempt) {
        return new EmissionCommand("1", emissionId, "b4c1a2d3-84f5-4a4c-8c0f-112233445566",
                "w-2026-0142", "t-8842", "+40722111222", "SILENT_TP0", "226-10", attempt, 3,
                Instant.parse("2026-09-16T12:00:00Z"), Instant.parse("2026-09-16T12:10:00Z"));
    }

    private static EmissionHandler collector(List<String> seen, RuntimeException failure) {
        return new EmissionHandler() {
            @Override
            public void onCommand(EmissionCommand command) {
                seen.add(command.emissionId() + ":" + command.attempt());
                if (failure != null) {
                    throw failure;
                }
            }

            @Override
            public void onRejected(String rawPayload, EmissionConsumer.RejectReason reason) {
                // Not under test: this double only exercises the command path.
            }
        };
    }

    @Test
    void forwardsEachEmissionExactlyOnce() throws Exception {
        List<String> seen = new ArrayList<>();
        DedupingEmissionHandler handler = new DedupingEmissionHandler(
                DedupeStore.open(tempDir.resolve("dedupe.ids"), 100), collector(seen, null));

        handler.onCommand(command(ID, 1));
        handler.onCommand(command(ID, 1)); // redelivery of the same attempt
        handler.onCommand(command("5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6f", 1));

        assertEquals(List.of(ID + ":1", "5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6f:1"), seen);
        assertEquals(1, handler.duplicateCount());
    }

    @Test
    void retriesOfTheSameEmissionAreExecuted() throws Exception {
        List<String> seen = new ArrayList<>();
        DedupingEmissionHandler handler = new DedupingEmissionHandler(
                DedupeStore.open(tempDir.resolve("dedupe.ids"), 100), collector(seen, null));

        handler.onCommand(command(ID, 1));
        handler.onCommand(command(ID, 1)); // duplicate
        handler.onCommand(command(ID, 2)); // retry of the same emission — must run
        handler.onCommand(command(ID, 3));

        assertEquals(List.of(ID + ":1", ID + ":2", ID + ":3"), seen);
        assertEquals(1, handler.duplicateCount());
    }

    @Test
    void duplicatesAreDroppedAcrossHandlerInstances() throws Exception {
        Path file = tempDir.resolve("dedupe.ids");
        List<String> seen = new ArrayList<>();

        new DedupingEmissionHandler(DedupeStore.open(file, 100), collector(seen, null))
                .onCommand(command(ID, 1));
        new DedupingEmissionHandler(DedupeStore.open(file, 100), collector(seen, null))
                .onCommand(command(ID, 1));

        assertEquals(List.of(ID + ":1"), seen, "a restart must not re-execute an attempt");
    }

    @Test
    void aFailureBeforeTheSendReleasesTheClaimSoRedeliveryRetries() throws Exception {
        Path file = tempDir.resolve("dedupe.ids");
        DedupeStore store = DedupeStore.open(file, 100);
        List<String> seen = new ArrayList<>();
        RuntimeException failure = new IllegalStateException("disk full");
        DedupingEmissionHandler failing =
                new DedupingEmissionHandler(store, collector(seen, failure));

        assertThrows(IllegalStateException.class, () -> failing.onCommand(command(ID, 1)));
        assertEquals(0, store.size(), "an unexecuted attempt must not stay claimed");

        List<String> retried = new ArrayList<>();
        new DedupingEmissionHandler(store, collector(retried, null)).onCommand(command(ID, 1));
        assertEquals(List.of(ID + ":1"), retried, "the redelivery of a released claim must execute");
    }

    @Test
    void aFailureAfterTheSendKeepsTheClaimToPreventADoubleSend() throws Exception {
        DedupeStore store = DedupeStore.open(tempDir.resolve("dedupe.ids"), 100);
        List<String> seen = new ArrayList<>();
        DedupingEmissionHandler handler = new DedupingEmissionHandler(store, new EmissionHandler() {
            @Override
            public void onCommand(EmissionCommand command) {
                seen.add(command.emissionId());
                throw new EmissionExecutionException(true, "cannot report ACCEPTED", null);
            }

            @Override
            public void onRejected(String rawPayload, EmissionConsumer.RejectReason reason) {
                // Not under test: this double only exercises the command path.
            }
        });

        assertThrows(EmissionExecutionException.class, () -> handler.onCommand(command(ID, 1)));
        assertEquals(1, store.size(), "the claim must survive a reporting failure after the send");

        // The redelivery is deduped (no exception) — nothing reaches the modem twice.
        handler.onCommand(command(ID, 1));
        assertEquals(1, seen.size(), "a redelivery must never reach the modem twice");
        assertEquals(1, handler.duplicateCount());
    }

    @Test
    void rejectedPayloadsPassThroughToTheDelegate() {
        List<String> rejected = new ArrayList<>();
        DedupingEmissionHandler handler = new DedupingEmissionHandler(
                DedupeStore.open(tempDir.resolve("dedupe.ids"), 10),
                new EmissionHandler() {
                    @Override
                    public void onCommand(EmissionCommand command) {
                        // Not under test: this double only exercises the rejection path.
                    }

                    @Override
                    public void onRejected(String rawPayload, EmissionConsumer.RejectReason reason) {
                        rejected.add(rawPayload);
                    }
                });

        handler.onRejected("{bad", EmissionConsumer.RejectReason.INVALID);
        assertEquals(List.of("{bad"), rejected);
    }
}
