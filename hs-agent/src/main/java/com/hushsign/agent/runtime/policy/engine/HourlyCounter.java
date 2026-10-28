package com.hushsign.agent.runtime.policy.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.List;
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
 *
 * <p>Persistent on purpose. A ceiling that starts again at zero whenever the process does is
 * not a ceiling: systemd restarts the agent on failure, and the failure it restarts from is
 * often the modem, which takes five seconds. Every increment is appended to a journal and
 * forced, and the current hour is replayed at startup, so "six per hour" survives a crash, a
 * restart and a power cut.
 *
 * <p>A clock that jumps backwards cannot open a fresh bucket either: the hour used is the
 * greatest one ever seen, so a rewind reuses the bucket it left rather than starting a new
 * allowance.
 */
public final class HourlyCounter {

    private final Clock clock;
    private final Path journal;
    private final Map<Bucket, AtomicLong> counts = new ConcurrentHashMap<>();
    private long highWaterHour;

    /** Memory only - for tests and for callers that have no work directory. */
    public HourlyCounter(Clock clock) {
        this(clock, null);
    }

    /**
     * @param journal where counts are appended; {@code null} keeps them in memory
     */
    public HourlyCounter(Clock clock, Path journal) {
        this.clock = clock;
        this.journal = journal;
        if (journal != null) {
            replay();
        }
        this.highWaterHour = Math.max(highWaterHour, currentHour());
    }

    /**
     * Counts one event and returns the total for the current hour <em>including</em> it, so
     * a caller can compare against its ceiling and {@link #decrement} when it refuses.
     */
    public long incrementAndGet(String key) {
        Bucket bucket = bucket(key);
        prune(bucket.hour());
        long count = counts.computeIfAbsent(bucket, ignored -> new AtomicLong()).incrementAndGet();
        append(bucket, '+');
        return count;
    }

    public long count(String key) {
        AtomicLong counter = counts.get(bucket(key));
        return counter == null ? 0 : counter.get();
    }

    /** Gives back a count taken by {@link #incrementAndGet}, for a decision that refused. */
    public void decrement(String key) {
        Bucket bucket = bucket(key);
        counts.computeIfPresent(bucket, (ignored, counter) -> counter.decrementAndGet() <= 0 ? null : counter);
        append(bucket, '-');
    }

    private Bucket bucket(String key) {
        return new Bucket(key, currentHour());
    }

    private long currentHour() {
        long now = clock.instant().getEpochSecond() / 3600;
        highWaterHour = Math.max(highWaterHour, now);
        return highWaterHour;
    }

    private void prune(long hour) {
        counts.keySet().removeIf(bucket -> bucket.hour() < hour);
    }

    private void append(Bucket bucket, char operation) {
        if (journal == null) {
            return;
        }
        String line = bucket.hour() + "\t" + operation + bucket.key() + System.lineSeparator();
        try {
            try (var channel = java.nio.channels.FileChannel.open(journal,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                channel.write(java.nio.ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8)));
                channel.force(false);
            }
        } catch (IOException e) {
            // Losing the journal must not lose the send: the count in memory is still right for
            // this process, and the next start is the only thing that suffers.
            throw new UncheckedIOException("cannot append to " + journal, e);
        }
    }

    /**
     * Rebuilds the counts of the newest hour in the journal. Older hours are dropped: they were
     * pruned while the agent ran and they are pruned again now.
     */
    private void replay() {
        if (!Files.exists(journal)) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(journal, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + journal, e);
        }
        for (String line : lines) {
            int tab = line.indexOf('\t');
            if (tab < 1 || line.length() < tab + 2) {
                continue;
            }
            long hour;
            try {
                hour = Long.parseLong(line.substring(0, tab));
            } catch (NumberFormatException _) {
                continue; // a half-written last line after a power cut
            }
            char operation = line.charAt(tab + 1);
            String key = line.substring(tab + 2);
            highWaterHour = Math.max(highWaterHour, hour);
            if (hour != highWaterHour) {
                continue;
            }
            counts.computeIfAbsent(new Bucket(key, hour), ignored -> new AtomicLong())
                    .addAndGet(operation == '-' ? -1 : 1);
        }
        counts.entrySet().removeIf(entry -> entry.getValue().get() <= 0);
    }

    private record Bucket(String key, long hour) {
    }
}

