package com.hushsign.agent.runtime.policy;

import com.hushsign.protocol.v1.EmissionCommand;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-S11: the development stub allows everything, with a fixed reason and no
 * state, so early wiring behaves identically on every call.
 */
class AcceptAllPolicyProviderTest {

    private static final EmissionCommand COMMAND = new EmissionCommand(
            "1", "5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e",
            "b4c1a2d3-84f5-4a4c-8c0f-112233445566", "w-2026-0142", "t-8842",
            "+40722111222", "SILENT_TP0", "226-10", 1, 3,
            Instant.parse("2026-09-16T12:00:00Z"), Instant.parse("2026-09-16T12:10:00Z"));

    private final AcceptAllPolicyProvider provider = new AcceptAllPolicyProvider();

    @Test
    void allowsEveryEmission() {
        PolicyDecision decision = provider.evaluate(COMMAND);

        assertTrue(decision.allowed());
    }

    @Test
    void reasonIsTheFixedStubReason() {
        assertEquals(AcceptAllPolicyProvider.REASON, provider.evaluate(COMMAND).reason());
    }

    @Test
    void isStatelessAcrossCalls() {
        assertEquals(provider.evaluate(COMMAND).reason(), provider.evaluate(COMMAND).reason());
        assertTrue(provider.evaluate(COMMAND).allowed());
    }
}
