package com.hushsign.protocol;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The freshness gate: refuses commands whose {@code issuedAt} is too old —
 * this bounds the post-revocation exposure of an agent that was offline
 * to {@code maxCommandAgeSeconds} (default 300 s).
 *
 * <p>A {@code clockSkewAllowanceSeconds} tolerance (default 120 s) applies in
 * both directions: a command older than {@code maxAge + skew} is STALE, and a
 * command issued more than {@code skew} in the future is CLOCK_SKEW (refused).
 */
public final class FreshnessGate {

    public static final Duration DEFAULT_MAX_COMMAND_AGE = Duration.ofSeconds(300);
    public static final Duration DEFAULT_CLOCK_SKEW_ALLOWANCE = Duration.ofSeconds(120);

    public enum Freshness {
        /** Command age is within limits: proceed. */
        FRESH,
        /** Command is older than maxCommandAge (+ skew allowance): refuse. */
        STALE,
        /** Command is dated more than the skew allowance in the future: refuse. */
        CLOCK_SKEW
    }

    private final Clock clock;
    private final Duration maxCommandAge;
    private final Duration clockSkewAllowance;

    public FreshnessGate() {
        this(Clock.systemUTC());
    }

    public FreshnessGate(Clock clock) {
        this(clock, DEFAULT_MAX_COMMAND_AGE, DEFAULT_CLOCK_SKEW_ALLOWANCE);
    }

    public FreshnessGate(Clock clock, Duration maxCommandAge, Duration clockSkewAllowance) {
        if (maxCommandAge.isNegative() || clockSkewAllowance.isNegative()) {
            throw new IllegalArgumentException("maxCommandAge and clockSkewAllowance must not be negative");
        }
        this.clock = clock;
        this.maxCommandAge = maxCommandAge;
        this.clockSkewAllowance = clockSkewAllowance;
    }

    public Freshness check(Instant issuedAt) {
        return check(issuedAt, Instant.now(clock));
    }

    public Freshness check(Instant issuedAt, Instant now) {
        if (issuedAt.isAfter(now.plus(clockSkewAllowance))) {
            return Freshness.CLOCK_SKEW;
        }
        if (now.isAfter(issuedAt.plus(maxCommandAge).plus(clockSkewAllowance))) {
            return Freshness.STALE;
        }
        return Freshness.FRESH;
    }
}
