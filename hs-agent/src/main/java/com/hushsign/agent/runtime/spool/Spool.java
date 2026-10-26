package com.hushsign.agent.runtime.spool;

import com.hushsign.protocol.ProtocolJson;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * P1-S8: disk-backed, ordered outbound buffer. Results, heartbeats and
 * digests are enqueued when the broker is unreachable and flushed FIFO on
 * reconnect.
 *
 * <ul>
 *   <li>{@link #enqueue} appends one JSONL line and is durable immediately;
 *       a failed persist rolls the record back and throws
 *       {@link SpoolException}.</li>
 *   <li>{@link #flushTo} sends oldest-first; the first failure stops the
 *       flush and leaves the failed record plus all newer ones spooled, so
 *       order is preserved across reconnects.</li>
 *   <li>Acks rewrite the file atomically (temp + move); the spool survives
 *       agent restarts.</li>
 * </ul>
 *
 * <p>At-least-once: a record whose send succeeded but whose ack was lost to
 * a crash may be resent; downstream consumers are idempotent by key.
 */
public final class Spool {

    /** Default cap for the on-disk buffer: a Pi must not fill its SD card in an outage. */
    public static final long DEFAULT_MAX_BYTES = 64L * 1024 * 1024;

    /** Consecutive non-retriable send failures before the head record is quarantined. */
    static final int QUARANTINE_AFTER = 5;

    private final Path file;
    private final Path poisonFile;
    private final long maxBytes;
    private final Deque<SpooledRecord> records = new ArrayDeque<>();

    /** Consecutive failures of the current head record (reset on progress). */
    private int headFailures;
    private long quarantined;

    private Spool(Path file, long maxBytes) {
        this.file = file.toAbsolutePath();
        this.poisonFile = this.file.resolveSibling(this.file.getFileName() + ".poison.jsonl");
        if (maxBytes < 1) {
            throw new IllegalArgumentException("spool maxBytes must be >= 1, got " + maxBytes);
        }
        this.maxBytes = maxBytes;
    }

    /** Opens (creating if needed) the spool backed by {@code file}. */
    public static Spool open(Path file) {
        return open(file, DEFAULT_MAX_BYTES);
    }

    /** Opens the spool with an explicit byte cap. */
    public static Spool open(Path file, long maxBytes) {
        Spool spool = new Spool(file, maxBytes);
        spool.load();
        return spool;
    }

    /**
     * Durably appends one outbound record.
     *
     * <p>Fails closed when the spool is at its byte cap: an agent that cannot
     * record evidence must not keep sending. The caller treats the exception as
     * a failed attempt and retries once the broker drains the buffer.
     */
    public synchronized void enqueue(String topic, String key, String value) {
        SpooledRecord record = new SpooledRecord(topic, key, value);
        long projected = sizeBytes() + ProtocolJson.write(record).length() + 1L;
        if (projected > maxBytes) {
            throw new SpoolException("spool is full (" + sizeBytes() + " of " + maxBytes
                    + " bytes); refusing to drop evidence for " + topic);
        }
        records.addLast(record);
        try {
            appendLine(ProtocolJson.write(record));
        } catch (IOException e) {
            records.removeLast(); // fail closed: nothing spooled that isn't on disk
            throw new SpoolException("cannot persist spooled record for " + topic, e);
        }
    }

    public synchronized int size() {
        return records.size();
    }

    /** Oldest-first snapshot; does not remove anything. */
    public synchronized List<SpooledRecord> peekAll() {
        return List.copyOf(records);
    }

    /**
     * Flushes oldest-first through {@code sender}. A retriable failure (broker
     * unreachable, timeout, rebalance) stops the flush and keeps the failed
     * record plus everything newer, preserving order across reconnects. A
     * record rejected repeatedly for a non-retriable reason is moved to the
     * poison file instead of blocking every later record forever.
     *
     * @return the number of records published
     */
    public synchronized int flushTo(SpoolSender sender) {
        int sent = 0;
        int removed = 0;
        for (SpooledRecord record : records) {
            try {
                sender.send(record);
            } catch (Exception e) {
                if (isPermanent(e) && ++headFailures >= QUARANTINE_AFTER) {
                    quarantine(record);
                    quarantined++;
                    removed++;
                    headFailures = 0;
                    continue;
                }
                break;
            }
            headFailures = 0;
            sent++;
            removed++;
        }
        if (removed > 0) {
            ackFirst(removed);
        }
        return sent;
    }

    /** Records moved aside because they could never be published. */
    public synchronized long quarantined() {
        return quarantined;
    }

    /** The side file poison records are appended to. */
    public Path poisonFile() {
        return poisonFile;
    }

    /** Bytes currently buffered (UTF-8 payload + newline), used for the cap. */
    public synchronized long sizeBytes() {
        long total = 0;
        for (SpooledRecord record : records) {
            total += ProtocolJson.write(record).getBytes(StandardCharsets.UTF_8).length + 1L;
        }
        return total;
    }

    /**
     * True when retrying cannot help: Kafka API errors that are not
     * {@link org.apache.kafka.common.errors.RetriableException} (record too
     * large, topic authorization, serialization) — as opposed to an outage.
     */
    static boolean isPermanent(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof org.apache.kafka.common.errors.RetriableException) {
                return false;
            }
            if (current instanceof org.apache.kafka.common.errors.ApiException
                    || current instanceof org.apache.kafka.common.errors.SerializationException) {
                return true;
            }
            if (current instanceof java.util.concurrent.TimeoutException
                    || current instanceof IllegalStateException
                    || current instanceof SpoolException) {
                return false;
            }
            current = current.getCause();
        }
        return false;
    }

    private void quarantine(SpooledRecord record) {
        try {
            appendTo(poisonFile, ProtocolJson.write(record));
        } catch (IOException e) {
            // Keep the record buffered: better a blocked spool than a silent loss.
            throw new SpoolException("cannot quarantine spooled record for " + record.topic(), e);
        }
    }

    private static void appendTo(Path target, String json) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(target, json + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Removes the first {@code n} records after they were published. */
    public synchronized void ackFirst(int n) {
        if (n < 0 || n > records.size()) {
            throw new IllegalArgumentException("cannot ack " + n + " of " + records.size() + " spooled records");
        }
        List<SpooledRecord> removed = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            removed.add(records.removeFirst());
        }
        try {
            rewriteFile();
        } catch (IOException e) {
            // restore the in-memory state so it matches the on-disk state
            for (int i = removed.size() - 1; i >= 0; i--) {
                records.addFirst(removed.get(i));
            }
            throw new SpoolException("cannot persist spool ack", e);
        }
    }

    public Path file() {
        return file;
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    records.addLast(ProtocolJson.read(line, SpooledRecord.class));
                } catch (RuntimeException malformed) {
                    // a torn tail write after a crash: skip it, keep the valid prefix
                }
            }
        } catch (IOException e) {
            throw new SpoolException("cannot load spool " + file, e);
        }
    }

    /**
     * Appends one line and forces it to the device: the spool exists to survive a
     * power cut on a field gateway, and a buffered write without {@code force}
     * can lose the tail exactly when it matters.
     */
    private void appendLine(String json) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        byte[] line = (json + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap(line);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(false);
        }
    }

    /** Rewrites the file to mirror the current queue exactly (atomic move). */
    private void rewriteFile() throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (BufferedWriter writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (SpooledRecord record : records) {
                writer.write(ProtocolJson.write(record));
                writer.newLine();
            }
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
