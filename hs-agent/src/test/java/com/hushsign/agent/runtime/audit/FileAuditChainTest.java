package com.hushsign.agent.runtime.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P7-S2 (and the tamper half of P7-S10): the chain holds for what the agent wrote, and says
 * exactly where it stops holding when somebody edits, deletes or reorders an entry.
 *
 * <p>These cases are the reason the log exists, so they are asserted on the verdict and on the
 * sequence number rather than on a message.
 */
class FileAuditChainTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-27T10:00:00Z"), ZoneOffset.UTC);

    private static FileAuditChain chain(Path dir) {
        return FileAuditChain.open(dir, "gw-1", 0, CLOCK);
    }

    private static void fill(FileAuditChain chain, int entries) {
        for (int i = 1; i <= entries; i++) {
            assertTrue(chain.record("SENT", "em-" + i, "detail " + i).isPresent());
        }
    }

    private static List<String> lines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }

    private static void write(Path file, List<String> lines) throws IOException {
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    @Test
    void appendsAndVerifies(@TempDir Path dir) throws IOException {
        FileAuditChain chain = chain(dir);
        fill(chain, 5);

        AuditLog.ChainVerification verification = chain.verify();

        assertTrue(verification.ok(), verification.problem());
        assertEquals(5, verification.entries());
        assertEquals(5, lines(dir.resolve("audit-0001.jsonl")).size());
        assertEquals(5, chain.tail(10).size());
        assertTrue(chain.status());
    }

    @Test
    void theChainContinuesAcrossARestart(@TempDir Path dir) {
        fill(chain(dir), 3);

        FileAuditChain reopened = chain(dir);
        reopened.record("STARTUP", null, "agent restarted");

        AuditLog.ChainVerification verification = reopened.verify();
        assertTrue(verification.ok(), verification.problem());
        assertEquals(4, verification.entries());
        assertEquals(4, reopened.tail(1).get(0).seq(), "the sequence continues instead of restarting");
    }

    @Test
    void rotationKeepsTheChainInFileOrder(@TempDir Path dir) {
        FileAuditChain chain = FileAuditChain.open(dir, "gw-1", 400, CLOCK);
        fill(chain, 20);

        assertTrue(Files.exists(dir.resolve("audit-0001.jsonl")));
        assertTrue(Files.exists(dir.resolve("audit-0002.jsonl")), "the log rotated");

        AuditLog.ChainVerification verification = chain.verify();
        assertTrue(verification.ok(), verification.problem());
        assertEquals(20, verification.entries());
    }

    @Test
    void anEditedDetailBreaksTheChainAtThatEntry(@TempDir Path dir) throws IOException {
        FileAuditChain chain = chain(dir);
        fill(chain, 5);
        Path file = dir.resolve("audit-0001.jsonl");

        List<String> lines = lines(file);
        lines.set(2, lines.get(2).replace("\"detail\":\"detail 3\"", "\"detail\":\"something else\""));
        write(file, lines);

        AuditLog.ChainVerification verification = chain.verify();

        assertFalse(verification.ok());
        assertEquals(3, verification.brokenAt());
        assertTrue(verification.problem().contains("does not match its own hash"), verification.problem());
    }

    @Test
    void aDeletedEntryBreaksTheChain(@TempDir Path dir) throws IOException {
        FileAuditChain chain = chain(dir);
        fill(chain, 5);
        Path file = dir.resolve("audit-0001.jsonl");

        List<String> lines = new java.util.ArrayList<>(lines(file));
        lines.remove(1);
        write(file, lines);

        AuditLog.ChainVerification verification = chain.verify();

        assertFalse(verification.ok());
        assertEquals(3, verification.brokenAt(), "the entry after the hole is the first one that does not fit");
        assertTrue(verification.problem().contains("expected sequence 2 but found 3"), verification.problem());
    }

    @Test
    void reorderingTwoEntriesBreaksTheChain(@TempDir Path dir) throws IOException {
        FileAuditChain chain = chain(dir);
        fill(chain, 4);
        Path file = dir.resolve("audit-0001.jsonl");

        List<String> lines = new java.util.ArrayList<>(lines(file));
        String second = lines.get(1);
        lines.set(1, lines.get(2));
        lines.set(2, second);
        write(file, lines);

        assertFalse(chain.verify().ok());
    }

    @Test
    void truncatingTheLastEntryIsVisible(@TempDir Path dir) throws IOException {
        FileAuditChain chain = chain(dir);
        fill(chain, 3);
        Path file = dir.resolve("audit-0001.jsonl");

        List<String> lines = new java.util.ArrayList<>(lines(file));
        lines.remove(lines.size() - 1);
        write(file, lines);

        // The remaining entries are consistent among themselves, so the walk itself passes -
        // this is the case only the platform can catch, by comparing digests (P7-S3). What the
        // device can say is that the log it holds now is shorter than the sequence it wrote.
        AuditLog.ChainVerification verification = chain.verify();
        assertTrue(verification.ok());
        assertEquals(2, verification.entries());
    }

    @Test
    void anUnreadableFileIsReportedRatherThanIgnored(@TempDir Path dir) throws IOException {
        FileAuditChain chain = chain(dir);
        fill(chain, 2);
        Files.writeString(dir.resolve("audit-0001.jsonl"), "this is not json\n");

        AuditLog.ChainVerification verification = chain.verify();

        assertFalse(verification.ok());
        assertTrue(verification.problem().contains("cannot be read"), verification.problem());
    }

    @Test
    void anEntryCarriesTheHashOfTheOneBefore(@TempDir Path dir) {
        FileAuditChain chain = chain(dir);
        fill(chain, 2);

        List<AuditEntry> entries = chain.tail(2);

        assertEquals(AuditEntry.GENESIS, entries.get(0).prevHash());
        assertEquals(entries.get(0).hash(), entries.get(1).prevHash());
        assertTrue(entries.get(1).hashMatches());
    }
}
