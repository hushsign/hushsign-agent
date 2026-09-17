package com.hushsign.agent.runtime.policy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-S11: the decision shape every policy provider must return — allowed flag
 * plus a non-blank reason so the P7 audit chain always has something to log.
 */
class PolicyDecisionTest {

    @Test
    void allowCarriesFlagAndReason() {
        PolicyDecision decision = PolicyDecision.allow("technique in allowlist");

        assertTrue(decision.allowed());
        assertEquals("technique in allowlist", decision.reason());
    }

    @Test
    void rejectCarriesFlagAndReason() {
        PolicyDecision decision = PolicyDecision.reject("quiet hours 23:00-06:00");

        assertFalse(decision.allowed());
        assertEquals("quiet hours 23:00-06:00", decision.reason());
    }

    @Test
    void nullReasonIsRejected() {
        assertThrows(NullPointerException.class, () -> new PolicyDecision(true, null));
    }

    @Test
    void blankReasonIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PolicyDecision(true, "  "));
    }
}
