package com.hushsign.agent.runtime.kafka;

import com.hushsign.agent.runtime.dedupe.DedupeStore;
import com.hushsign.protocol.v1.EmissionCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P1-S7 tests: the dedupe decorator forwards each emission exactly once and
 * drops redeliveries before the execution pipeline sees them.
 */
class DedupingEmissionHandlerTest {

    @TempDir
    Path tempDir;

    private static EmissionCommand command(String emissionId) {
        return new EmissionCommand("1", emissionId, "b4c1a2d3-84f5-4a4c-8c0f-112233445566",
                "w-2026-0142", "t-8842", "+40722111222", "SILENT_TP0", "226-10", 1, 3,
                Instant.parse("2026-09-16T12:00:00Z"), Instant.parse("2026-09-16T12:10:00Z"));
    }

    @Test
    void forwardsEachEmissionExactlyOnce() throws Exception {
        List<String> seen = new ArrayList<>();
        DedupingEmissionHandler handler = new DedupingEmissionHandler(
                DedupeStore.open(tempDir.resolve("dedupe.ids"), 100),
                new EmissionHandler() {
                    @Override
                    public void onCommand(EmissionCommand command) {
                        seen.add(command.emissionId());
                    }

                    @Override
                    public void onRejected(String rawPayload, EmissionConsumer.RejectReason reason) {
                    }
                });

        String id = "5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e";
        handler.onCommand(command(id));
        handler.onCommand(command(id)); // redelivery
        handler.onCommand(command("5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6f"));

        assertEquals(List.of(id, "5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6f"), seen);
        assertEquals(1, handler.duplicateCount());
    }

    @Test
    void duplicatesAreDroppedAcrossHandlerInstances() throws Exception {
        Path file = tempDir.resolve("dedupe.ids");
        List<String> seen = new ArrayList<>();
        EmissionHandler collector = new EmissionHandler() {
            @Override
            public void onCommand(EmissionCommand command) {
                seen.add(command.emissionId());
            }

            @Override
            public void onRejected(String rawPayload, EmissionConsumer.RejectReason reason) {
            }
        };
        String id = "5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e";

        new DedupingEmissionHandler(DedupeStore.open(file, 100), collector).onCommand(command(id));
        new DedupingEmissionHandler(DedupeStore.open(file, 100), collector).onCommand(command(id));

        assertEquals(List.of(id), seen, "a restart must not re-execute an emission");
    }

    @Test
    void rejectedPayloadsPassThroughToTheDelegate() {
        List<String> rejected = new ArrayList<>();
        DedupingEmissionHandler handler = new DedupingEmissionHandler(
                DedupeStore.open(tempDir.resolve("dedupe.ids"), 10),
                new EmissionHandler() {
                    @Override
                    public void onCommand(EmissionCommand command) {
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
