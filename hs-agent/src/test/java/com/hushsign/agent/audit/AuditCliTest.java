package com.hushsign.agent.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hushsign.agent.runtime.audit.AuditEntry;
import com.hushsign.agent.runtime.audit.FileAuditChain;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P7-S2: the offline tool an auditor runs. Its exit code is the verdict, so the tests assert
 * the code as well as the output - a script has to be able to gate on it.
 */
class AuditCliTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-27T10:00:00Z"), ZoneOffset.UTC);

    private static Path chainIn(Path workDir) {
        FileAuditChain chain = FileAuditChain.open(workDir.resolve("audit"), "gw-1", 0, CLOCK);
        chain.record("STARTUP", null, "agent version test");
        chain.record("POLICY_ALLOW", "em-1", "within caps");
        chain.record("POLICY_REJECT", "em-2", "quiet hours 23:00-06:00");
        return workDir;
    }

    @Test
    void verifyExitsZeroOnAnIntactChain(@TempDir Path dir) throws IOException {
        Path workDir = chainIn(dir);

        assertEquals(0, AuditCli.run(new String[] {"verify", "--dir", workDir.toString()}));
    }

    @Test
    void verifyExitsOneOnATamperedChain(@TempDir Path dir) throws IOException {
        Path workDir = chainIn(dir);
        Path file = workDir.resolve("audit").resolve("audit-0001.jsonl");
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        lines.set(1, lines.get(1).replace("within caps", "within caps (edited)"));
        Files.write(file, lines, StandardCharsets.UTF_8);

        assertEquals(1, AuditCli.run(new String[] {"verify", "--dir", workDir.toString()}));
    }

    @Test
    void verifyExitsOneWhenThereIsNoChainAtAll(@TempDir Path dir) throws IOException {
        // "OK: 0 entries, chain intact" is what a deleted chain says. It reads as a pass, and an
        // operator who has just checked a device that has been sending for weeks believes it.
        Path empty = Files.createDirectories(dir.resolve("empty"));

        assertEquals(1, AuditCli.run(new String[] {"verify", "--dir", empty.toString()}));
    }

    @Test
    void tailPrintsTheNewestEntries(@TempDir Path dir) throws IOException {
        Path workDir = chainIn(dir);

        assertEquals(0, AuditCli.run(new String[] {"tail", "--dir", workDir.toString(), "--count", "2"}));
    }

    @Test
    void exportWritesTheCanonicalLines(@TempDir Path dir) throws IOException {
        Path workDir = chainIn(dir);
        Path out = dir.resolve("evidence.txt");

        assertEquals(0, AuditCli.run(new String[] {"export", "--dir", workDir.toString(), "--out", out.toString()}));

        List<String> lines = Files.readAllLines(out, StandardCharsets.UTF_8);
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).startsWith("1|2026-10-27T10:00:00Z|gw-1|STARTUP|"), lines.get(0));
        assertTrue(lines.get(1).contains("|POLICY_ALLOW|em-1|"));
        AuditEntry first = FileAuditChain.open(workDir.resolve("audit"), "gw-1", 0, CLOCK).tail(3).get(0);
        assertTrue(lines.get(0).endsWith("|" + first.hash()), "the exported line carries the chain hash");
    }

    @Test
    void usageProblemsExitWithTwo(@TempDir Path dir) throws IOException {
        assertEquals(2, AuditCli.run(new String[] {}));
        assertEquals(2, AuditCli.run(new String[] {"verify"}));
        assertEquals(2, AuditCli.run(new String[] {"nonsense", "--dir", dir.toString()}));
        assertEquals(2, AuditCli.run(new String[] {"export", "--dir", dir.toString()}));
    }
}
