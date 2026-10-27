package com.hushsign.agent.runtime.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * One line of the agent's audit chain (P7-S2).
 *
 * <p>An entry is evidence of what the device did, so its hash covers everything that came
 * before it as well as its own content: {@code hash = sha256(canonical)} where the canonical
 * form ends with the previous entry's hash. Changing a detail, changing a sequence number, or
 * removing an entry therefore breaks the chain at the point where it happened, and
 * {@link AuditLog#verify()} can say where.
 *
 * <p>The canonical form is a fixed field order joined with {@code |} - not JSON - because it
 * has to be reproducible byte for byte by anyone who has the entry, with no parser.
 */
public record AuditEntry(
        long seq,
        Instant at,
        String gatewayId,
        String type,
        String emissionId,
        String detail,
        String prevHash,
        String hash) {

    public static final String GENESIS = "genesis";

    /**
     * Where a chain starts: {@code sha256("genesis")}. A digest of the very first hour reports
     * this as its {@code chainPrev}, which keeps the field 64 lowercase hex — the wire format
     * cannot express "nothing yet", and a fixed value is easier to verify than a null.
     */
    public static final String GENESIS_HASH = hashOf(GENESIS);

    public AuditEntry {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(gatewayId, "gatewayId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(hash, "hash");
    }

    /** Builds the next entry of a chain: the hash is computed here, never supplied. */
    public static AuditEntry next(long seq, Instant at, String gatewayId, String type, String emissionId,
                                  String detail, String prevHash) {
        String previous = prevHash == null || prevHash.isBlank() ? GENESIS : prevHash;
        AuditEntry unsigned = new AuditEntry(seq, at, gatewayId, type, emissionId, detail, previous, "");
        return new AuditEntry(seq, at, gatewayId, type, emissionId, detail, previous,
                hashOf(unsigned.canonical()));
    }

    /** The bytes the hash covers: this entry followed by the hash it continues. */
    public String canonical() {
        return seq + "|" + at + "|" + gatewayId + "|" + type + "|" + orDash(emissionId) + "|"
                + orDash(detail) + "|" + prevHash;
    }

    public static String hashOf(String canonical) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** True when this entry's hash is what its own content and {@code prevHash} produce. */
    public boolean hashMatches() {
        return hashOf(canonical()).equals(hash);
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value.replace('|', '/').replace('\n', ' ');
    }
}
