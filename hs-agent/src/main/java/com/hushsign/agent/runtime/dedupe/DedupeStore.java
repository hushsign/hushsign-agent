package com.hushsign.agent.runtime.dedupe;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * P1-S7: persisted, bounded dedupe store by {@code emissionId} — redelivery
 * can never double-send, across restarts too.
 *
 * <ul>
 *   <li>{@link #tryClaim} is atomic and immediately persisted: {@code true}
 *       only for the first claim of an id, {@code false} for redeliveries.</li>
 *   <li>One append-only line per claim; the window keeps the newest
 *       {@code maxEntries} ids, evicting the oldest and rewriting the file
 *       (atomic temp-file move) so the file always matches the window.</li>
 *   <li>If persisting fails, the in-memory claim is rolled back and a
 *       {@link DedupeException} propagates — fail closed, never double-send.</li>
 * </ul>
 */
public final class DedupeStore {

    private final Path file;
    private final int maxEntries;
    private final LinkedHashSet<String> ids = new LinkedHashSet<>();

    private DedupeStore(Path file, int maxEntries) {
        this.file = file.toAbsolutePath();
        this.maxEntries = maxEntries;
        load();
    }

    /** Opens (creating if needed) a store backed by {@code file} with a bounded window. */
    public static DedupeStore open(Path file, int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be >= 1, got " + maxEntries);
        }
        return new DedupeStore(file, maxEntries);
    }

    /**
     * Claims an emissionId. {@code true} = first sighting, proceed;
     * {@code false} = already processed, drop. Never returns false after true
     * for the same id while the id stays inside the window.
     *
     * @throws DedupeException when the claim cannot be persisted
     */
    public synchronized boolean tryClaim(String emissionId) {
        if (emissionId == null || emissionId.isBlank()) {
            throw new IllegalArgumentException("emissionId must not be blank");
        }
        if (ids.contains(emissionId)) {
            return false;
        }
        ids.add(emissionId);
        try {
            if (ids.size() > maxEntries) {
                evictOldest();
                rewriteFile();
            } else {
                appendLine(emissionId);
            }
        } catch (IOException e) {
            ids.remove(emissionId); // roll back: a failed persist must not count as claimed
            throw new DedupeException("cannot persist dedupe claim for " + emissionId, e);
        }
        return true;
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
            throw new DedupeException("cannot load dedupe store " + file, e);
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
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
