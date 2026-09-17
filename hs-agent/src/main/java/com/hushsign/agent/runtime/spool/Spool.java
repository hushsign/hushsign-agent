package com.hushsign.agent.runtime.spool;

import com.hushsign.protocol.ProtocolJson;

import java.io.BufferedWriter;
import java.io.IOException;
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

    private final Path file;
    private final Deque<SpooledRecord> records = new ArrayDeque<>();

    private Spool(Path file) {
        this.file = file.toAbsolutePath();
    }

    /** Opens (creating if needed) the spool backed by {@code file}. */
    public static Spool open(Path file) {
        Spool spool = new Spool(file);
        spool.load();
        return spool;
    }

    /** Durably appends one outbound record. */
    public synchronized void enqueue(String topic, String key, String value) {
        SpooledRecord record = new SpooledRecord(topic, key, value);
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
     * Flushes oldest-first through {@code sender}; stops at the first send
     * failure and acks exactly what was sent. Returns the number of records
     * published.
     */
    public synchronized int flushTo(SpoolSender sender) {
        int sent = 0;
        for (SpooledRecord record : records) {
            try {
                sender.send(record);
            } catch (Exception e) {
                break; // broker unreachable: keep this record and everything newer
            }
            sent++;
        }
        if (sent > 0) {
            ackFirst(sent);
        }
        return sent;
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

    private void appendLine(String json) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(json);
            writer.newLine();
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
