package com.hushsign.protocol;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FreshnessGateTest {

    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");

    private static FreshnessGate gate() {
        return new FreshnessGate(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void justIssuedCommandIsFresh() {
        assertEquals(FreshnessGate.Freshness.FRESH, gate().check(NOW));
    }

    @Test
    void commandAtMaxAgePlusSkewBoundaryIsFresh() {
        Instant issuedAt = NOW.minus(Duration.ofSeconds(300 + 120));
        assertEquals(FreshnessGate.Freshness.FRESH, gate().check(issuedAt));
    }

    @Test
    void commandOlderThanMaxAgePlusSkewIsStale() {
        Instant issuedAt = NOW.minus(Duration.ofSeconds(300 + 121));
        assertEquals(FreshnessGate.Freshness.STALE, gate().check(issuedAt));
    }

    @Test
    void commandFromTheFutureWithinSkewIsFresh() {
        Instant issuedAt = NOW.plus(Duration.ofSeconds(120));
        assertEquals(FreshnessGate.Freshness.FRESH, gate().check(issuedAt));
    }

    @Test
    void commandFromTheFutureBeyondSkewIsClockSkew() {
        Instant issuedAt = NOW.plus(Duration.ofSeconds(121));
        assertEquals(FreshnessGate.Freshness.CLOCK_SKEW, gate().check(issuedAt));
    }

    @Test
    void customThresholdsAreHonored() {
        FreshnessGate gate = new FreshnessGate(Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(60), Duration.ofSeconds(30));
        assertEquals(FreshnessGate.Freshness.FRESH, gate.check(NOW.minusSeconds(90)));
        assertEquals(FreshnessGate.Freshness.STALE, gate.check(NOW.minusSeconds(91)));
    }
}
