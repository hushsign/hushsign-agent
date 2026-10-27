package com.hushsign.agent.runtime.policy.engine;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts events per wall-clock hour (P7-S1).
 *
 * <p>Deliberately simple: what this feeds is a safety ceiling, not a billing meter, and an
 * hour bucket is easy to explain to an auditor ("no more than six to one number in an
 * hour"). Buckets of past hours are pruned as they are used, so a long-running agent cannot
 * accumulate one entry per key per hour forever.
 */
public final class HourlyCounter {

    private final Clock clock;
    private final Map<Bucket, AtomicLong> counts = new ConcurrentHashMap<>();

    public HourlyCounter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Counts one event and returns the total for the current hour <em>including</em> it, so
     * a caller can compare against its ceiling and {@link #decrement} when it refuses.
     */
    public long incrementAndGet(String key) {
        Bucket bucket = bucket(key);
        prune(bucket.hour());
        return counts.computeIfAbsent(bucket, ignored -> new AtomicLong()).incrementAndGet();
    }

    public long count(String key) {
        AtomicLong counter = counts.get(bucket(key));
        return counter == null ? 0 : counter.get();
    }

    /** Gives back a count taken by {@link #incrementAndGet}, for a decision that refused. */
    public void decrement(String key) {
        counts.computeIfPresent(bucket(key), (ignored, counter) -> counter.decrementAndGet() <= 0 ? null : counter);
    }

    private Bucket bucket(String key) {
        return new Bucket(key, clock.instant().getEpochSecond() / 3600);
    }

    private void prune(long hour) {
        counts.keySet().removeIf(bucket -> bucket.hour() < hour);
    }

    private record Bucket(String key, long hour) {
    }
}
