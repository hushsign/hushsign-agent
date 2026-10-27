package com.hushsign.agent.runtime.policy.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** P7-S1: the ceiling counters move with the wall clock and do not grow without bound. */
class HourlyCounterTest {

    /** A clock the test can move, so an hour boundary is something we can step over. */
    private static final class MovableClock extends Clock {

        private Instant now = Instant.parse("2026-10-27T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }
    }

    private final MovableClock clock = new MovableClock();
    private final HourlyCounter counter = new HourlyCounter(clock);

    @Test
    void countsWithinTheHour() {
        assertEquals(1, counter.incrementAndGet("target:+40700000001"));
        assertEquals(2, counter.incrementAndGet("target:+40700000001"));
        assertEquals(2, counter.count("target:+40700000001"));
        assertEquals(0, counter.count("target:+40700000002"), "keys are independent");
    }

    @Test
    void theCountStartsAgainInTheNextHour() {
        counter.incrementAndGet("gateway");
        counter.incrementAndGet("gateway");

        clock.advance(Duration.ofMinutes(61));

        assertEquals(0, counter.count("gateway"));
        assertEquals(1, counter.incrementAndGet("gateway"));
    }

    @Test
    void aRefusedDecisionCanGiveItsCountBack() {
        counter.incrementAndGet("operator:226-10");

        counter.decrement("operator:226-10");

        assertEquals(0, counter.count("operator:226-10"));
        counter.decrement("operator:226-10");
        assertEquals(0, counter.count("operator:226-10"), "a decrement below zero is a no-op");
    }

    @Test
    void theCeilingSurvivesARestart(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        // systemd restarts this process every time the modem hiccups. A ceiling that starts
        // again at zero on each restart is six per restart, which is not a ceiling.
        java.nio.file.Path journal = dir.resolve("policy-counters.journal");
        HourlyCounter before = new HourlyCounter(clock, journal);
        before.incrementAndGet("target:+40700000001");
        before.incrementAndGet("target:+40700000001");
        before.incrementAndGet("target:+40700000001");

        HourlyCounter afterRestart = new HourlyCounter(clock, journal);

        assertEquals(3, afterRestart.count("target:+40700000001"));
        assertEquals(4, afterRestart.incrementAndGet("target:+40700000001"));
    }

    @Test
    void aGivenBackCountIsNotResurrectedByARestart(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        java.nio.file.Path journal = dir.resolve("policy-counters.journal");
        HourlyCounter before = new HourlyCounter(clock, journal);
        before.incrementAndGet("target:+40700000001");
        before.decrement("target:+40700000001");

        assertEquals(0, new HourlyCounter(clock, journal).count("target:+40700000001"));
    }

    @Test
    void theJournalDoesNotAwardAFreshHourWhenTheClockGoesBackwards(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        java.nio.file.Path journal = dir.resolve("policy-counters.journal");
        HourlyCounter counter = new HourlyCounter(clock, journal);
        counter.incrementAndGet("target:+40700000001");
        clock.now = Instant.parse("2026-10-27T09:10:00Z");

        HourlyCounter rewound = new HourlyCounter(clock, journal);

        assertEquals(1, rewound.count("target:+40700000001"),
                "a backward clock jump reuses the bucket it left instead of opening a new one");
    }
}
