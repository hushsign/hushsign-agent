package com.hushsign.agent.runtime.policy.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hushsign.agent.runtime.policy.PolicyDecision;
import com.hushsign.protocol.v1.EmissionCommand;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * P7-S9: the visible technique needs two keys, and each one alone must refuse.
 *
 * <p>The story the user asked for was "add it disabled, not enabled-by-default". A single flag
 * would mean that a catalogue paste, or a careless edit on one side, switches on visible
 * messaging for a live gateway; two independent keys make that a deliberate act in two places.
 */
class VisibleSmsPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");
    private static final String EVERYTHING = "SMS_DELIVERY";

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private PolicyDecision evaluate(Set<String> allowedTechniques, boolean visibleSms) {
        PolicySettings settings = new PolicySettings(false, allowedTechniques, Set.of(),
                PolicySettings.Caps.UNLIMITED, null, null, visibleSms);
        return new PolicyEngine(settings, "gw-1", new HourlyCounter(this.clock), this.clock)
                .evaluate(command());
    }

    private static EmissionCommand command() {
        return new EmissionCommand("1", "em-1", "s-1", "W-1", "t-1", "+40700000001", EVERYTHING,
                "226-10", 1, 3, NOW.minusSeconds(5), NOW.plusSeconds(300));
    }

    @Test
    void visibleSmsOffRefusesEvenWhenTheAllowListNamesIt() {
        PolicyDecision decision = evaluate(Set.of(EVERYTHING), false);

        assertFalse(decision.allowed());
        assertEquals("visible SMS is not enabled (visibleSms: true in policy.yaml)", decision.reason());
    }

    @Test
    void absentFromTheAllowListRefusesEvenWhenVisibleSmsIsOn() {
        PolicyDecision decision = evaluate(Set.of("SILENT_TP0"), true);

        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("not in the allow list"), decision.reason());
    }

    @Test
    void bothKeysTogetherAllowIt() {
        assertTrue(evaluate(Set.of(EVERYTHING), true).allowed());
    }

    @Test
    void anEmptyAllowListDoesNotOpenTheVisibleTechnique() {
        // "empty means any" is the documented behaviour for the silent catalogue. It must not
        // extend to the visible one, or a policy with no technique list would send an SMS.
        assertFalse(evaluate(Set.of(), false).allowed());
        assertTrue(evaluate(Set.of(), true).allowed(),
                "an empty list plus the explicit switch is a deliberate choice and may proceed");
    }

    @Test
    void theSilentTechniquesAreUnaffectedByTheVisibleSwitch() {
        PolicySettings settings = new PolicySettings(false, Set.of(), Set.of(),
                PolicySettings.Caps.UNLIMITED, null, null, false);
        EmissionCommand silent = new EmissionCommand("1", "em-2", "s-1", "W-1", "t-1", "+40700000001",
                "SILENT_TP0", "226-10", 1, 3, NOW.minusSeconds(5), NOW.plusSeconds(300));

        PolicyDecision decision = new PolicyEngine(settings, "gw-1", new HourlyCounter(this.clock), this.clock)
                .evaluate(silent);

        assertTrue(decision.allowed());
    }
}
