package com.hushsign.agent.runtime.kafka;

import com.hushsign.agent.runtime.policy.PolicyDecision;
import com.hushsign.agent.runtime.policy.PolicyProvider;
import com.hushsign.protocol.v1.EmissionCommand;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-S11: the policy gate in front of the execution pipeline. Allowed
 * emissions reach the delegate; rejected ones (or provider failures, treated
 * fail-closed) are dropped and counted — nothing reaches a modem.
 */
class PolicyCheckingEmissionHandlerTest {

    private static EmissionCommand command(String emissionId) {
        return new EmissionCommand(
                "1", emissionId, "b4c1a2d3-84f5-4a4c-8c0f-112233445566", "w-2026-0142", "t-8842",
                "+40722111222", "SILENT_TP0", "226-10", 1, 3,
                Instant.parse("2026-09-16T12:00:00Z"), Instant.parse("2026-09-16T12:10:00Z"));
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

    @Test
    void allowedEmissionReachesTheDelegate() throws Exception {
        RecordingHandler delegate = new RecordingHandler();
        PolicyProvider allowAll = cmd -> PolicyDecision.allow("ok");
        PolicyCheckingEmissionHandler handler = new PolicyCheckingEmissionHandler(allowAll, delegate);

        handler.onCommand(command("5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e"));

        assertEquals(1, delegate.commands.size());
        assertEquals("5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e", delegate.commands.get(0).emissionId());
        assertEquals(0, handler.policyRejectionCount());
    }

    @Test
    void rejectedEmissionIsDroppedAndCounted() throws Exception {
        RecordingHandler delegate = new RecordingHandler();
        PolicyProvider denyAll = cmd -> PolicyDecision.reject("kill switch");
        PolicyCheckingEmissionHandler handler = new PolicyCheckingEmissionHandler(denyAll, delegate);

        handler.onCommand(command("5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e"));

        assertTrue(delegate.commands.isEmpty());
        assertEquals(1, handler.policyRejectionCount());
    }

    @Test
    void providerFailureFailsClosed() throws Exception {
        RecordingHandler delegate = new RecordingHandler();
        PolicyProvider throwing = cmd -> {
            throw new IllegalStateException("policy file unreadable");
        };
        PolicyCheckingEmissionHandler handler = new PolicyCheckingEmissionHandler(throwing, delegate);

        handler.onCommand(command("5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e"));

        assertTrue(delegate.commands.isEmpty());
        assertEquals(1, handler.policyRejectionCount());
    }

    @Test
    void protocolRejectionsPassThroughToTheDelegate() {
        RecordingHandler delegate = new RecordingHandler();
        PolicyCheckingEmissionHandler handler =
                new PolicyCheckingEmissionHandler(cmd -> PolicyDecision.allow("ok"), delegate);

        handler.onRejected("{bad json", EmissionConsumer.RejectReason.INVALID);

        assertEquals(1, delegate.rejectedPayloads.size());
        assertEquals(EmissionConsumer.RejectReason.INVALID, delegate.rejectedReasons.get(0));
        assertEquals(0, handler.policyRejectionCount());
    }
}
