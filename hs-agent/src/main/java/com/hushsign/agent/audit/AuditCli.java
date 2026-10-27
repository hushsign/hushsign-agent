package com.hushsign.agent.audit;

import com.hushsign.agent.runtime.audit.AuditEntry;
import com.hushsign.agent.runtime.audit.AuditLog;
import com.hushsign.agent.runtime.audit.FileAuditChain;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

/**
 * {@code hs-agent audit verify|tail|export} (P7-S2).
 *
 * <p>The tool an auditor runs on the device - or on a copy of its log directory - without
 * starting the agent. It shares {@link FileAuditChain} with the runtime, so "the device says
 * the chain is fine" and "the tool says the chain is fine" are the same code path rather than
 * two implementations that could drift.
 *
 * <pre>
 * java -cp hs-agent.jar com.hushsign.agent.audit.AuditCli verify --dir /var/lib/hushsign
 * java -cp hs-agent.jar com.hushsign.agent.audit.AuditCli tail   --dir ... --count 20
 * java -cp hs-agent.jar com.hushsign.agent.audit.AuditCli export --dir ... --out evidence.txt
 * </pre>
 *
 * <p>The exit code is the verdict: 0 when the chain holds (or the requested output was
 * produced), 1 when it does not, 2 for a usage problem, so a script can gate on it.
 */
public final class AuditCli {

    private AuditCli() {
    }

    public static void main(String[] args) throws IOException {
        System.exit(run(args));
    }

    /** Same as {@link #main} but returns the exit code, so it can be tested. */
    public static int run(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: audit verify|tail|export [--dir DIR] [--count N] [--out FILE]");
            return 2;
        }
        String command = args[0];
        Path dir = null;
        Path out = null;
        int count = 20;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--dir" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("--dir needs a value");
                        return 2;
                    }
                    dir = Path.of(args[++i]);
                }
                case "--out" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("--out needs a value");
                        return 2;
                    }
                    out = Path.of(args[++i]);
                }
                case "--count" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("--count needs a value");
                        return 2;
                    }
                    count = Integer.parseInt(args[++i]);
                }
                default -> {
                    System.err.println("unknown option " + args[i]);
                    return 2;
                }
            }
        }
        if (dir == null) {
            System.err.println("--dir is required (the agent's work directory, or a copy of its audit folder)");
            return 2;
        }
        Path auditDir = Files.isDirectory(dir.resolve("audit")) ? dir.resolve("audit") : dir;
        FileAuditChain chain = FileAuditChain.open(auditDir, "cli", 0, Clock.systemUTC());

        return switch (command) {
            case "verify" -> verify(chain);
            case "tail" -> tail(chain, count);
            case "export" -> export(chain, out);
            default -> {
                System.err.println("unknown command " + command + " (verify|tail|export)");
                yield 2;
            }
        };
    }

    private static int verify(FileAuditChain chain) {
        AuditLog.ChainVerification verification = chain.verify();
        if (verification.ok()) {
            System.out.println("OK: " + verification.entries() + " entries, chain intact (" + chain.location() + ")");
            return 0;
        }
        System.out.println("FAILED: " + verification.entries() + " entries read, broken at sequence "
                + verification.brokenAt() + ": " + verification.problem());
        return 1;
    }

    private static int tail(FileAuditChain chain, int count) {
        List<AuditEntry> entries = chain.tail(count);
        entries.forEach(entry -> System.out.println(entry.seq() + "  " + entry.at() + "  " + entry.type() + "  "
                + entry.emissionId() + "  " + entry.detail()));
        return 0;
    }

    private static int export(FileAuditChain chain, Path out) throws IOException {
        if (out == null) {
            System.err.println("--out is required for export");
            return 2;
        }
        List<AuditEntry> entries = chain.tail(Integer.MAX_VALUE);
        StringBuilder text = new StringBuilder();
        for (AuditEntry entry : entries) {
            text.append(entry.seq()).append('|').append(entry.at()).append('|').append(entry.gatewayId())
                    .append('|').append(entry.type()).append('|')
                    .append(entry.emissionId() == null ? "-" : entry.emissionId()).append('|')
                    .append(entry.detail() == null ? "-" : entry.detail()).append('|')
                    .append(entry.prevHash()).append('|').append(entry.hash()).append('\n');
        }
        Files.writeString(out, text, StandardCharsets.UTF_8);
        System.out.println("exported " + entries.size() + " entries to " + out);
        return 0;
    }
}
