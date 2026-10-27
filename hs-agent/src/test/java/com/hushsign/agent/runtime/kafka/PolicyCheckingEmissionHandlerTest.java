package com.hushsign.agent.runtime.kafka;

import com.hushsign.agent.runtime.policy.PolicyDecision;
import com.hushsign.agent.runtime.policy.PolicyProvider;
import com.hushsign.agent.runtime.transmission.TransmissionRecorder;
import com.hushsign.protocol.v1.EmissionCommand;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-S11: the policy gate in front of the execution pipeline. Allowed
 * emissions reach the delegate; rejected ones (or provider failures, treated
 * fail-closed) never reach a modem and are reported as
 * {@code BLOCKED_BY_POLICY} so the platform settles them on evidence.
 */
class PolicyCheckingEmissionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-09-16T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static EmissionCommand command(String emissionId) {
        return new EmissionCommand(
                "1", emissionId, "b4c1a2d3-84f5-4a4c-8c0f-112233445566", "w-2026-0142", "t-8842",
                "+40722111222", "SILENT_TP0", "226-10", 1, 3,
                Instant.parse("2026-09-16T11:59:00Z"), Instant.parse("2026-09-16T12:10:00Z"));
    }

    private static final class RecordingHandler implements EmissionHandler {
        final List<EmissionCommand> commands = new ArrayList<>();
        final List<String> rejectedPayloads = new ArrayList<>();
        final List<EmissionConsumer.RejectReason> rejectedReasons = new ArrayList<>();

        @Override
        public void onCommand(EmissionCommand command) {
            commands.add(command);
        }

        @Override
        public void onRejected(String rawPayload, EmissionConsumer.RejectReason reason) {
            rejectedPayloads.add(rawPayload);
            rejectedReasons.add(reason);
        }
    }

    /** Captures what the gate records instead of a send. */
    private static final class RecordingRecorder implements TransmissionRecorder {
        final List<String> outcomes = new ArrayList<>();
        final List<String> causes = new ArrayList<>();
        final List<Instant> requestedAt = new ArrayList<>();

        @Override
        public void record(EmissionCommand command, String outcome, String failureCause, Instant when) {
            outcomes.add(outcome);
            causes.add(failureCause);
            requestedAt.add(when);
        }
    }

    @Test
    void allowedEmissionReachesTheDelegate() throws Exception {
        RecordingHandler delegate = new RecordingHandler();
        RecordingRecorder recorder = new RecordingRecorder();
        PolicyCheckingEmissionHandler handler = new PolicyCheckingEmissionHandler(
                cmd -> PolicyDecision.allow("ok"), delegate, recorder, CLOCK);

        handler.onCommand(command("5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e"));

        assertEquals(1, delegate.commands.size());
        assertEquals("5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e", delegate.commands.get(0).emissionId());
        assertEquals(0, handler.policyRejectionCount());
        assertTrue(recorder.outcomes.isEmpty(), "an allowed emission is reported by the executor, not here");
    }

    @Test
    void rejectedEmissionIsDroppedAndReportedAsBlockedByPolicy() throws Exception {
        RecordingHandler delegate = new RecordingHandler();
        RecordingRecorder recorder = new RecordingRecorder();
        PolicyCheckingEmissionHandler handler = new PolicyCheckingEmissionHandler(
                cmd -> PolicyDecision.reject("kill switch"), delegate, recorder, CLOCK);

        handler.onCommand(command("5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e"));

        assertTrue(delegate.commands.isEmpty());
        assertEquals(1, handler.policyRejectionCount());
        assertEquals(List.of("BLOCKED_BY_POLICY"), recorder.outcomes);
        assertEquals(List.of("kill switch"), recorder.causes);
        assertEquals(List.of(NOW), recorder.requestedAt);
    }

    @Test
    void theReasonThatLeavesTheDeviceCarriesNoTargetNumber() throws Exception {
        // The cap reason names the number that hit the ceiling. It may say so in the local
        // chain, but hs.transmissions.v1 is not allowed to carry a target number, and scrubbing
        // it later on the platform is too late: by then it is in the topic's segments.
        RecordingHandler delegate = new RecordingHandler();
        RecordingRecorder recorder = new RecordingRecorder();
        PolicyCheckingEmissionHandler handler = new PolicyCheckingEmissionHandler(
                cmd -> PolicyDecision.reject("target " + cmd.msisdn() + " has reached its 6-per-hour cap"),
                delegate, recorder, CLOCK);

        handler.onCommand(command("5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e"));

        String cause = recorder.causes.get(0);
        assertTrue(cause.contains("*"), "the number must be masked: " + cause);
        assertTrue(cause.endsWith("has reached its 6-per-hour cap"), cause);
        assertTrue(cause.indexOf("722111222") < 0, "the raw number must not leave the gateway: " + cause);
    }

    @Test
    void providerFailureFailsClosed() throws Exception {        RecordingHandler delegate = new RecordingHandler();
        RecordingRecorder recorder = new RecordingRecorder();
        PolicyProvider throwing = cmd -> {
            throw new IllegalStateException("policy file unreadable");
        };
        PolicyCheckingEmissionHandler handler =
                new PolicyCheckingEmissionHandler(throwing, delegate, recorder, CLOCK);

        handler.onCommand(command("5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e"));

        assertTrue(delegate.commands.isEmpty());
        assertEquals(1, handler.policyRejectionCount());
        assertEquals(List.of("BLOCKED_BY_POLICY"), recorder.outcomes);
        assertTrue(recorder.causes.get(0).startsWith("policy provider failed:"));
    }

    @Test
    void protocolRejectionsPassThroughToTheDelegate() {
        RecordingHandler delegate = new RecordingHandler();
        RecordingRecorder recorder = new RecordingRecorder();
        PolicyCheckingEmissionHandler handler = new PolicyCheckingEmissionHandler(
                cmd -> PolicyDecision.allow("ok"), delegate, recorder, CLOCK);

        handler.onRejected("{bad json", EmissionConsumer.RejectReason.INVALID);

        assertEquals(1, delegate.rejectedPayloads.size());
        assertEquals(EmissionConsumer.RejectReason.INVALID, delegate.rejectedReasons.get(0));
        assertEquals(0, handler.policyRejectionCount());
        assertTrue(recorder.outcomes.isEmpty());
    }
}
