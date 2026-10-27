package com.hushsign.agent.runtime.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hushsign.agent.runtime.policy.PolicyDecision;
import com.hushsign.agent.runtime.policy.engine.HourlyCounter;
import com.hushsign.agent.runtime.policy.engine.PolicyEngine;
import com.hushsign.agent.runtime.policy.engine.PolicySettings;
import com.hushsign.protocol.v1.EmissionCommand;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * P7-S4: what the engine does when an operator acts, and that a reload takes effect on the next
 * command without letting anyone reset a cap.
 */
class AdminControlledPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AdminState state = new AdminState();
    private final HourlyCounter counter = new HourlyCounter(this.clock);

    private PolicyEngine engine(PolicySettings settings) {
        return new PolicyEngine(settings, "gw-1", this.counter, this.clock, this.state);
    }

    private static PolicySettings open() {
        return new PolicySettings(false, Set.of(), Set.of(), null, null, null);
    }

    private static EmissionCommand command() {
        return new EmissionCommand("1", "em-1", "s-1", "W-1", "t-1", "+40700000001", "SILENT_TP0",
                "226-10", 1, 3, NOW.minusSeconds(5), NOW.plusSeconds(300));
    }

    @Test
    void aPlainCommandPassesBeforeAnyoneActs() {
        assertTrue(engine(open()).evaluate(command()).allowed());
    }

    @Test
    void aPausedAgentRefusesAndSaysWhy() {
        this.state.pause();
        PolicyDecision decision = engine(open()).evaluate(command());

        assertFalse(decision.allowed());
        assertEquals("agent is paused by the operator", decision.reason());
    }

    @Test
    void resumingLetsCommandsThroughAgain() {
        this.state.pause();
        this.state.resume();

        assertTrue(engine(open()).evaluate(command()).allowed());
    }

    @Test
    void aKilledAgentRefusesEverything() {
        this.state.kill();
        PolicyDecision decision = engine(open()).evaluate(command());

        assertFalse(decision.allowed());
        assertEquals("kill switch engaged", decision.reason());
    }

    @Test
    void theKillSwitchAnswersBeforeThePause() {
        this.state.pause();
        this.state.kill();

        assertEquals("kill switch engaged", engine(open()).evaluate(command()).reason(),
                "the stronger statement is the one the operator needs to see");
    }

    @Test
    void aReloadChangesTheRulesOnTheNextCommand() {
        PolicyEngine engine = engine(open());
        assertTrue(engine.evaluate(command()).allowed());

        engine.applyPolicy(new PolicySettings(false, Set.of("MWI_DEACTIVATE"), Set.of(), null, null, null));

        PolicyDecision after = engine.evaluate(command());
        assertFalse(after.allowed());
        assertTrue(after.reason().contains("technique SILENT_TP0"), after.reason());
    }

    @Test
    void aReloadDoesNotResetTheHourlyCaps() {
        PolicySettings capped = new PolicySettings(false, Set.of(), Set.of(),
                new PolicySettings.Caps(1, 0, 0), null, null);
        PolicyEngine engine = engine(capped);
        assertTrue(engine.evaluate(command()).allowed(), "the first send is within the cap");

        // A reload is not a way to get another hour's allowance.
        engine.applyPolicy(capped);

        PolicyDecision second = engine.evaluate(command());
        assertFalse(second.allowed());
        assertTrue(second.reason().contains("cap"), second.reason());
    }
}
