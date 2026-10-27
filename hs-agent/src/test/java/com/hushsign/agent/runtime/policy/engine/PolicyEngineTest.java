package com.hushsign.agent.runtime.policy.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hushsign.agent.runtime.policy.PolicyDecision;
import com.hushsign.protocol.v1.EmissionCommand;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * P7-S1: every refusal the policy can make, and the order it makes them in.
 *
 * <p>The engine is the last thing between a command and a modem, so the tests are about the
 * ways it says no: a kill switch, an expired or stale command, a technique or operator
 * outside the allow list, quiet hours across midnight, and each of the three hourly caps.
 */
class PolicyEngineTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");
    private static final String GATEWAY = "gw-1";

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private PolicyEngine engine(PolicySettings settings) {
        return new PolicyEngine(settings, GATEWAY, new HourlyCounter(clock), clock);
    }

    private static PolicySettings settings(boolean killSwitch, Set<String> techniques, Set<String> operators,
                                           PolicySettings.Caps caps, PolicySettings.QuietHours quietHours,
                                           Duration maxAge) {
        return new PolicySettings(killSwitch, techniques, operators, caps, quietHours, maxAge);
    }

    private static EmissionCommand command(String technique, String operator, String msisdn, Instant issuedAt,
                                           Instant expiresAt) {
        return new EmissionCommand("1", "em-1", "s-1", "W-1", "t-1", msisdn, technique, operator, 1, 3,
                issuedAt, expiresAt);
    }

    private static EmissionCommand fresh() {
        return command("SILENT_TP0", "226-10", "+40700000001", NOW.minusSeconds(5), NOW.plusSeconds(300));
    }

    @Test
    void aPlainCommandPassesAnEmptyPolicy() {
        PolicyDecision decision = engine(settings(false, Set.of(), Set.of(), null, null, null)).evaluate(fresh());

        assertTrue(decision.allowed());
    }

    @Test
    void theKillSwitchRefusesEverythingFirst() {
        PolicyDecision decision = engine(settings(true, Set.of("SILENT_TP0"), Set.of("226-10"),
                PolicySettings.Caps.UNLIMITED, null, null))
                .evaluate(command("EXPIRED", "999-99", null, NOW.minusSeconds(600), NOW.minusSeconds(500)));

        assertFalse(decision.allowed());
        assertEquals("kill switch engaged", decision.reason(), "the kill switch answers before the rest");
    }

    @Test
    void anExpiredCommandIsRefused() {
        PolicyDecision decision = engine(settings(false, Set.of(), Set.of(), null, null, null))
                .evaluate(command("SILENT_TP0", "226-10", "+40700000001", NOW.minusSeconds(600), NOW.minusSeconds(1)));

        assertFalse(decision.allowed());
        assertTrue(decision.reason().startsWith("command expired at"), decision.reason());
    }

    @Test
    void aCommandOlderThanTheFreshnessLimitIsRefused() {
        PolicyEngine engine = engine(settings(false, Set.of(), Set.of(), null, null, Duration.ofSeconds(60)));

        PolicyDecision decision = engine.evaluate(command("SILENT_TP0", "226-10", "+40700000001",
                NOW.minusSeconds(61), NOW.plusSeconds(600)));

        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("freshness limit"), decision.reason());
    }

    @Test
    void aCommandFromTheFutureIsRefused() {
        PolicyDecision decision = engine(settings(false, Set.of(), Set.of(), null, null, null))
                .evaluate(command("SILENT_TP0", "226-10", "+40700000001", NOW.plusSeconds(3600), null));

        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("issued in the future"), decision.reason());
    }

    @Test
    void aCommandWithoutAnIssueTimeIsRefused() {
        PolicyDecision decision = engine(settings(false, Set.of(), Set.of(), null, null, null))
                .evaluate(command("SILENT_TP0", "226-10", "+40700000001", null, null));

        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("no issue time"), decision.reason());
    }

    @Test
    void theTechniqueAndOperatorAllowListsAreEnforced() {
        PolicyEngine engine = engine(settings(false, Set.of("MWI_DEACTIVATE"), Set.of("226-01"), null, null, null));

        assertTrue(engine.evaluate(command("SILENT_TP0", "226-01", "+40700000001", NOW, null)).reason()
                .contains("technique SILENT_TP0 is not in the allow list"));
        assertTrue(engine.evaluate(command("MWI_DEACTIVATE", "226-10", "+40700000001", NOW, null)).reason()
                .contains("operator 226-10 is not in the allow list"));
        assertTrue(engine.evaluate(command("MWI_DEACTIVATE", "226-01", "+40700000001", NOW, null)).allowed());
    }

    @Test
    void quietHoursAcrossMidnightAreRefusedInsideAndAllowedOutside() {
        PolicySettings.QuietHours window = new PolicySettings.QuietHours(LocalTime.of(23, 0), LocalTime.of(6, 0),
                ZoneId.of("UTC"));
        PolicyEngine atNoon = engine(settings(false, Set.of(), Set.of(), null, window, null));
        assertTrue(atNoon.evaluate(fresh()).allowed(), "10:00 UTC is outside 23:00-06:00");

        Instant midnight = Instant.parse("2026-10-27T00:30:00Z");
        PolicyEngine atMidnight = new PolicyEngine(settings(false, Set.of(), Set.of(), null, window, null), GATEWAY,
                new HourlyCounter(Clock.fixed(midnight, ZoneOffset.UTC)),
                Clock.fixed(midnight, ZoneOffset.UTC));

        PolicyDecision decision = atMidnight.evaluate(command("SILENT_TP0", "226-10", "+40700000001",
                midnight.minusSeconds(5), null));
        assertFalse(decision.allowed());
        assertTrue(decision.reason().startsWith("quiet hours 23:00-06:00"), decision.reason());
    }

    @Test
    void quietHoursUseTheConfiguredZoneNotUtc() {
        PolicySettings.QuietHours window = new PolicySettings.QuietHours(LocalTime.of(23, 0), LocalTime.of(6, 0),
                ZoneId.of("Europe/Bucharest"));
        // 10:00 UTC is 12:00 in Bucharest in October (still EEST), so outside the window.
        PolicyEngine engine = engine(settings(false, Set.of(), Set.of(), null, window, null));
        assertTrue(engine.evaluate(fresh()).allowed());

        // 21:30 UTC is 23:30 in Bucharest, so inside it.
        Instant lateNight = Instant.parse("2026-10-27T21:30:00Z");
        PolicyEngine bucharestNight = new PolicyEngine(settings(false, Set.of(), Set.of(), null, window, null),
                GATEWAY, new HourlyCounter(Clock.fixed(lateNight, ZoneOffset.UTC)),
                Clock.fixed(lateNight, ZoneOffset.UTC));

        assertFalse(bucharestNight.evaluate(command("SILENT_TP0", "226-10", "+40700000001",
                lateNight.minusSeconds(5), null)).allowed());
    }

    @Test
    void thePerTargetCapStopsTheSeventhSendInAnHour() {
        PolicyEngine engine = engine(settings(false, Set.of(), Set.of(), new PolicySettings.Caps(6, 0, 0), null, null));

        for (int i = 0; i < 6; i++) {
            assertTrue(engine.evaluate(fresh()).allowed(), "send " + (i + 1));
        }
        PolicyDecision seventh = engine.evaluate(fresh());

        assertFalse(seventh.allowed());
        assertTrue(seventh.reason().contains("has reached its 6-per-hour cap"), seventh.reason());
    }

    @Test
    void aRefusalDoesNotSpendAnotherTargetsBudget() {
        // Two targets share a one-per-hour cap for the operator but have their own budgets.
        PolicyEngine engine = engine(settings(false, Set.of(), Set.of(), new PolicySettings.Caps(1, 0, 0), null, null));
        EmissionCommand first = command("SILENT_TP0", "226-10", "+40700000001", NOW, null);
        EmissionCommand second = command("SILENT_TP0", "226-10", "+40700000001", NOW, null);
        EmissionCommand other = command("SILENT_TP0", "226-10", "+40700000002", NOW, null);

        assertTrue(engine.evaluate(first).allowed());
        assertFalse(engine.evaluate(second).allowed(), "the second send to the same number is capped");
        assertTrue(engine.evaluate(other).allowed(), "another number still has its own budget");
    }

    @Test
    void theOperatorCapIgnoresTheTargetBudget() {
        PolicyEngine engine = engine(settings(false, Set.of(), Set.of(), new PolicySettings.Caps(0, 1, 0), null, null));

        assertTrue(engine.evaluate(command("SILENT_TP0", "226-10", "+40700000001", NOW, null)).allowed());
        PolicyDecision second = engine.evaluate(command("SILENT_TP0", "226-10", "+40700000002", NOW, null));

        assertFalse(second.allowed());
        assertTrue(second.reason().contains("operator 226-10 has reached its 1-per-hour cap"), second.reason());
    }

    @Test
    void theGatewayCapIsTheLastCeiling() {
        PolicyEngine engine = engine(settings(false, Set.of(), Set.of(), new PolicySettings.Caps(0, 0, 1), null, null));

        assertTrue(engine.evaluate(command("SILENT_TP0", "226-10", "+40700000001", NOW, null)).allowed());
        PolicyDecision second = engine.evaluate(command("SILENT_TP0", "226-01", "+40700000002", NOW, null));

        assertFalse(second.allowed());
        assertTrue(second.reason().contains("gateway " + GATEWAY + " has reached its 1-per-hour cap"), second.reason());
    }

    @Test
    void aCommandWithoutATechniqueOrTargetIsRefused() {
        PolicyEngine engine = engine(settings(false, Set.of(), Set.of(), null, null, null));

        assertTrue(engine.evaluate(command(null, "226-10", "+40700000001", NOW, null)).reason()
                .contains("no technique"));
        assertTrue(engine.evaluate(command("SILENT_TP0", null, "+40700000001", NOW, null)).reason()
                .contains("no operator"));
        assertTrue(engine.evaluate(command("SILENT_TP0", "226-10", null, NOW, null)).reason()
                .contains("no target number"));
        assertFalse(engine.evaluate(null).allowed());
    }

    @Test
    void noCapsMeansNoCeiling() {
        PolicyEngine engine = engine(settings(false, Set.of(), Set.of(), PolicySettings.Caps.UNLIMITED, null, null));

        for (int i = 0; i < 50; i++) {
            assertTrue(engine.evaluate(fresh()).allowed(), "send " + (i + 1));
        }
    }
}
