package com.hushsign.agent.runtime.cancel;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * P1-S13: the persisted, bounded set of emissions the platform cancelled.
 *
 * <p>Cancellation is the primary fail-closed mechanism after a warrant is
 * revoked (plan §5.2): an emission that is already queued must not be sent.
 * A cancellation therefore has to survive an agent restart, and it has to be
 * able to arrive <em>before</em> the emission it refers to — the two topics are
 * independent, so the store is a set, not a queue.
 *
 * <p>Bounded like the dedupe window: the newest {@code maxEntries} ids are
 * kept, older ones are evicted (by then the emission is long past its
 * {@code expiresAt} and the freshness gate refuses it anyway).
 */
public final class CancellationStore {

    private final Path file;
    private final int maxEntries;
    private final LinkedHashSet<String> ids = new LinkedHashSet<>();

    private CancellationStore(Path file, int maxEntries) {
        this.file = file.toAbsolutePath();
        this.maxEntries = maxEntries;
        load();
    }

    /** Opens (creating if needed) a cancellation set backed by {@code file}. */
    public static CancellationStore open(Path file, int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be >= 1, got " + maxEntries);
        }
        return new CancellationStore(file, maxEntries);
    }

    /**
     * Records a cancellation. Idempotent: recording the same emission twice
     * keeps one entry.
     *
     * @throws CancellationException when the record cannot be persisted
     */
    public synchronized void cancel(String emissionId) {
        if (emissionId == null || emissionId.isBlank()) {
            throw new IllegalArgumentException("emissionId must not be blank");
        }
        if (!ids.add(emissionId)) {
            return;
        }
        try {
            if (ids.size() > maxEntries) {
                evictOldest();
                rewriteFile();
            } else {
                appendLine(emissionId);
            }
        } catch (IOException e) {
            ids.remove(emissionId); // roll back: an unpersisted cancellation is not a cancellation
            throw new CancellationException("cannot persist cancellation for " + emissionId, e);
        }
    }

    /** Whether this emission was cancelled (known across restarts). */
    public synchronized boolean isCancelled(String emissionId) {
        return emissionId != null && ids.contains(emissionId);
    }

    public synchronized int size() {
        return ids.size();
    }

    public synchronized Set<String> snapshot() {
        return Set.copyOf(ids);
    }

    public Path file() {
        return file;
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                String id = line.trim();
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
            while (ids.size() > maxEntries) {
                evictOldest();
            }
        } catch (IOException e) {
            throw new CancellationException("cannot load cancellation store " + file, e);
        }
    }

    private void evictOldest() {
        Iterator<String> it = ids.iterator();
        it.next();
        it.remove();
    }

    private void appendLine(String id) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(id);
            writer.newLine();
        }
    }

    /** Rewrites the file to mirror the current window exactly (atomic move). */
    private void rewriteFile() throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (BufferedWriter writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (String id : ids) {
                writer.write(id);
                writer.newLine();
            }
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException _) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
