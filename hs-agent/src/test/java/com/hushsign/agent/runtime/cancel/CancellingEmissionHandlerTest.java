package com.hushsign.agent.runtime.cancel;

import com.hushsign.agent.runtime.kafka.EmissionConsumer;
import com.hushsign.agent.runtime.kafka.EmissionHandler;
import com.hushsign.agent.runtime.transmission.TransmissionRecorder;
import com.hushsign.protocol.v1.EmissionCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-S13: a cancelled emission never reaches the modem, and its refusal is
 * recorded as a {@code CANCELLED} transmission instead of vanishing.
 */
class CancellingEmissionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-09-17T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String ID = "5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e";

    @TempDir
    Path tempDir;

    private CancellationStore cancellations;
    private List<String> commands;
    private List<String> outcomes;
    private CancellingEmissionHandler handler;

    @BeforeEach
    void setUp() {
        cancellations = CancellationStore.open(tempDir.resolve("cancellations.ids"), 100);
        commands = new ArrayList<>();
        outcomes = new ArrayList<>();
        EmissionHandler delegate = new EmissionHandler() {
            @Override
            public void onCommand(EmissionCommand command) {
                commands.add(command.emissionId());
            }

            @Override
            public void onRejected(String rawPayload, EmissionConsumer.RejectReason reason) {
                // Not under test: this double only exercises the command path.
            }
        };
        TransmissionRecorder recorder = (command, outcome, cause, when) -> outcomes.add(outcome);
        handler = new CancellingEmissionHandler(cancellations, delegate, recorder, CLOCK);
    }

    private static EmissionCommand command() {
        return new EmissionCommand("1", ID, "b4c1a2d3-84f5-4a4c-8c0f-112233445566",
                "w-2026-0142", "t-8842", "+40722111222", "SILENT_TP0", "226-10", 1, 3,
                NOW.minusSeconds(10), NOW.plusSeconds(600));
    }

    @Test
    void aCancelledEmissionIsNotExecutedAndIsReportedAsCancelled() throws Exception {
        cancellations.cancel(ID);

        handler.onCommand(command());

        assertTrue(commands.isEmpty(), "a cancelled emission must never reach the modem");
        assertEquals(List.of("CANCELLED"), outcomes);
        assertEquals(1, handler.cancelledCount());
    }

    @Test
    void anUncancelledEmissionPassesThrough() throws Exception {
        handler.onCommand(command());

        assertEquals(List.of(ID), commands);
        assertTrue(outcomes.isEmpty());
        assertEquals(0, handler.cancelledCount());
    }
}
