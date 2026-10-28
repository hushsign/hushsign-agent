package com.hushsign.agent.runtime.audit;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The file-backed audit chain (P7-S2): one JSON line per entry, hash-chained, rotated by size.
 *
 * <p>Three properties make this usable as evidence on a device that can lose power:
 *
 * <ul>
 *   <li>every append is forced to disk before it is acknowledged, so an entry the caller
 *       believes is written survives a power cut;</li>
 *   <li>the chain is a sequence of {@code audit-0001.jsonl}, {@code audit-0002.jsonl}, ... and
 *       verification walks them in name order, so rotation cannot hide an entry;</li>
 *   <li>the log is append-only in code and in intent - there is no update and no delete
 *       operation, and tampering has to happen with an editor, which is exactly what
 *       {@link #verify()} catches.</li>
 * </ul>
 *
 * <p>The chain resumes from the last entry when the agent restarts, so a restart is visible as
 * a {@code STARTUP} entry rather than as a new chain.
 */
public final class FileAuditChain implements AuditLog {

    /** Wire names of the JSONL fields: one spelling for writing and for reading back. */
    private static final String FIELD_EMISSION_ID = "emissionId";
    private static final String FIELD_DETAIL = "detail";

    private static final Logger LOG = LoggerFactory.getLogger(FileAuditChain.class);
    private static final Pattern FILE_NAME = Pattern.compile("audit-(\\d{4})\\.jsonl");

    private final Path directory;
    private final String gatewayId;
    private final long maxFileBytes;
    private final Clock clock;
    private final Object writeLock = new Object();
    private final AtomicLong sequence;
    private String lastHash;
    private int fileIndex;
    private long bytesInFile;
    private volatile boolean status = true;

    private FileAuditChain(Path directory, String gatewayId, long maxFileBytes, Clock clock, long sequence,
                           String lastHash, int fileIndex, long bytesInFile) {
        this.directory = directory;
        this.gatewayId = gatewayId;
        this.maxFileBytes = maxFileBytes;
        this.clock = clock;
        this.sequence = new AtomicLong(sequence);
        this.lastHash = lastHash;
        this.fileIndex = fileIndex;
        this.bytesInFile = bytesInFile;
    }

    /**
     * Opens (or continues) the chain in {@code directory}.
     *
     * @param maxFileBytes rotate to the next file once this many bytes are written; 0 disables
     *                     rotation
     */
    public static FileAuditChain open(Path directory, String gatewayId, long maxFileBytes, Clock clock) {
        try {
            Files.createDirectories(directory);
            List<Path> files = files(directory);
            if (files.isEmpty()) {
                return new FileAuditChain(directory, gatewayId, maxFileBytes, clock, 0, null, 1, 0);
            }
            Path last = files.get(files.size() - 1);
            List<AuditEntry> entries = readEntries(last);
            AuditEntry tail = entries.isEmpty() ? null : entries.get(entries.size() - 1);
            long sequence = tail == null ? 0 : tail.seq();
            String hash = tail == null ? null : tail.hash();
            return new FileAuditChain(directory, gatewayId, maxFileBytes, clock, sequence, hash,
                    indexOf(last), Files.size(last));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open the audit chain in " + directory, e);
        }
    }

    @Override
    public Optional<AuditEntry> append(String type, String emissionId, String detail) {
        synchronized (writeLock) {
            AuditEntry entry = AuditEntry.next(sequence.get() + 1, Instant.now(clock), gatewayId, type,
                    emissionId, detail, lastHash);
            try {
                rotateIfNeeded(entry);
                append(currentFile(), new ObjectMapper().writeValueAsString(toJson(entry)) + "\n");
                sequence.set(entry.seq());
                lastHash = entry.hash();
                status = true;
                return Optional.of(entry);
            } catch (IOException | RuntimeException e) {
                status = false;
                LOG.error("cannot append to the audit chain in {}: {}", directory, e.toString());
                return Optional.empty();
            }
        }
    }

    @Override
    public ChainVerification verify() {
        List<Path> files = files(directory);
        Instant now = Instant.now(clock);
        long expectedSeq = 1;
        String expectedPrev = null;
        long seen = 0;
        for (Path file : files) {
            List<AuditEntry> entries;
            try {
                entries = read(file);
            } catch (IOException | RuntimeException e) {
                return ChainVerification.broken(seen, expectedSeq, file.getFileName() + " cannot be read: "
                        + e.getMessage(), now);
            }
            for (AuditEntry entry : entries) {
                seen++;
                if (entry.seq() != expectedSeq) {
                    return ChainVerification.broken(seen, entry.seq(), "expected sequence " + expectedSeq
                            + " but found " + entry.seq() + " in " + file.getFileName(), now);
                }
                if (expectedPrev != null && !expectedPrev.equals(entry.prevHash())) {
                    return ChainVerification.broken(seen, entry.seq(), "entry " + entry.seq()
                            + " does not continue the previous hash", now);
                }
                if (!entry.hashMatches()) {
                    return ChainVerification.broken(seen, entry.seq(), "entry " + entry.seq()
                            + " does not match its own hash (it was edited)", now);
                }
                expectedPrev = entry.hash();
                expectedSeq++;
            }
        }
        return ChainVerification.ok(seen, now);
    }

    @Override
    public List<AuditEntry> tail(int count) {
        List<Path> files = files(directory);
        List<AuditEntry> collected = new ArrayList<>();
        for (int i = files.size() - 1; i >= 0 && collected.size() < count; i--) {
            try {
                List<AuditEntry> entries = read(files.get(i));
                for (int j = entries.size() - 1; j >= 0 && collected.size() < count; j--) {
                    collected.add(0, entries.get(j));
                }
            } catch (IOException e) {
                LOG.warn("cannot read {} for tail: {}", files.get(i), e.toString());
            }
        }
        return List.copyOf(collected);
    }

    @Override
    public String location() {
        return directory.toAbsolutePath().toString();
    }

    @Override
    public Optional<DigestWindow> window(Instant from, Instant to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        long count = 0;
        String head = null;
        String previous = null;
        for (Path file : files(directory)) {
            List<AuditEntry> entries;
            try {
                entries = read(file);
            } catch (IOException e) {
                LOG.warn("cannot read {} for a digest window: {}", file, e.toString());
                return Optional.empty();
            }
            for (AuditEntry entry : entries) {
                if (entry.at().isBefore(from)) {
                    // Everything before the window tells us where the chain stood when it opened.
                    previous = entry.hash();
                } else if (entry.at().isBefore(to)) {
                    count++;
                    head = entry.hash();
                }
            }
        }
        return head == null ? Optional.empty() : Optional.of(new DigestWindow(count, head, previous));
    }

    @Override
    public boolean status() {
        return status;
    }

    private void rotateIfNeeded(AuditEntry entry) {
        if (maxFileBytes <= 0 || bytesInFile == 0 || bytesInFile < maxFileBytes) {
            return;
        }
        fileIndex++;
        bytesInFile = 0;
        LOG.info("audit chain rotated to {} (entry {})", fileName(fileIndex), entry.seq());
    }

    private void append(Path file, String line) throws IOException {
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            channel.write(java.nio.ByteBuffer.wrap(bytes));
            // An entry the caller believes is written must survive a power cut.
            channel.force(true);
        }
        bytesInFile += bytes.length;
    }

    private Path currentFile() {
        return directory.resolve(fileName(fileIndex));
    }

    private static String fileName(int index) {
        return "audit-%04d.jsonl".formatted(index);
    }

    private static List<Path> files(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> listed = Files.list(directory)) {
            return listed
                    .filter(Files::isRegularFile)
                    .filter(path -> FILE_NAME.matcher(path.getFileName().toString()).matches())
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list the audit chain in " + directory, e);
        }
    }

    private static int indexOf(Path file) {
        Matcher matcher = FILE_NAME.matcher(file.getFileName().toString());
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : 1;
    }

    private List<AuditEntry> read(Path file) throws IOException {
        return readEntries(file);
    }

    private static List<AuditEntry> readEntries(Path file) throws IOException {
        List<AuditEntry> entries = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                entries.add(fromJson(new ObjectMapper().readTree(line)));
            }
        }
        return entries;
    }

    private static ObjectNode toJson(AuditEntry entry) {
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("seq", entry.seq());
        node.put("at", entry.at().toString());
        node.put("gatewayId", entry.gatewayId());
        node.put("type", entry.type());
        node.put(FIELD_EMISSION_ID, entry.emissionId());
        node.put(FIELD_DETAIL, entry.detail());
        node.put("prevHash", entry.prevHash());
        node.put("hash", entry.hash());
        return node;
    }

    private static AuditEntry fromJson(tools.jackson.databind.JsonNode node) {
        return new AuditEntry(
                node.path("seq").asLong(),
                Instant.parse(node.path("at").asText()),
                node.path("gatewayId").asText(),
                node.path("type").asText(),
                node.hasNonNull(FIELD_EMISSION_ID) ? node.get(FIELD_EMISSION_ID).asText() : null,
                node.hasNonNull(FIELD_DETAIL) ? node.get(FIELD_DETAIL).asText() : null,
                node.path("prevHash").asText(),
                node.path("hash").asText());
    }
}
