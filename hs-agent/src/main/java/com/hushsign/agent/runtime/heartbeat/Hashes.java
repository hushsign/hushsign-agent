package com.hushsign.agent.runtime.heartbeat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * SHA-256 helpers for the gateway's policy/config hashes (P1-S9).
 */
public final class Hashes {

    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");
    private static final HexFormat HEX = HexFormat.of();

    private Hashes() {
    }

    public static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    /** The wire protocol requires hashes as 64 lowercase hex chars. */
    public static boolean isSha256Hex(String candidate) {
        return candidate != null && SHA256_HEX.matcher(candidate).matches();
    }
}
