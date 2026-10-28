package com.hushsign.agent.runtime.admin;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * How to reach the admin API, and the bearer token that unlocks it (P7-S4).
 *
 * <p>The user chose plain HTTP bound to loopback with a token in a file. That is a defensible
 * choice for a single-tenant device — the traffic never leaves the host — but it means the token
 * <em>is</em> the security boundary, so:
 *
 * <ul>
 *   <li>the listener binds {@code 127.0.0.1}; there is no option to bind another address;</li>
 *   <li>the token file is created {@code 0600};</li>
 *   <li>a missing token is never treated as "no authentication" — the server does not start.</li>
 * </ul>
 *
 * <p>Resolution order for the token: {@code HS_ADMIN_TOKEN} in the environment, then the file at
 * {@code HS_ADMIN_TOKEN_FILE} (default {@code admin.token} in the work directory), then a freshly
 * generated token written to that file. {@code HS_ADMIN_PORT=0} switches the API off entirely.
 */
public record AdminSettings(InetAddress bindAddress, int port, String token, Path tokenFile) {

    /** The environment variable that carries the port; 0 switches the API off. */
    static final String ENV_PORT = "HS_ADMIN_PORT";

    /** Loopback only. A remote admin surface is a different product with a different threat
     * model, so there is deliberately no way to bind another address. */
    static final String LOOPBACK = "127.0.0.1";

    public static final int DEFAULT_PORT = 9099;

    public static final String DEFAULT_TOKEN_FILE = "admin.token";

    private static final int TOKEN_BYTES = 32;

    /**
     * Reads the deployment's admin configuration, creating a token on first run.
     *
     * @return empty when the API is switched off with {@code HS_ADMIN_PORT=0}
     */
    public static Optional<AdminSettings> resolve(Path workDir) throws IOException {
        int port = intFromEnvironment(ENV_PORT, DEFAULT_PORT);
        if (port <= 0) {
            return Optional.empty();
        }
        Path tokenFile = tokenFile(workDir);
        String configuredToken = environment("HS_ADMIN_TOKEN", null);
        String token = configuredToken == null || configuredToken.isBlank()
                ? loadOrCreateToken(tokenFile)
                : configuredToken.trim();
        return Optional.of(new AdminSettings(InetAddress.getByName(LOOPBACK), port, token, tokenFile));
    }

    /**
     * Like {@link #resolve} but never writes anything: the client asking the agent a question
     * must not be the thing that creates the secret.
     *
     * @return empty when the API is off, or when no token exists yet to authenticate with
     */
    public static Optional<AdminSettings> locate(Path workDir) throws IOException {
        int port = intFromEnvironment(ENV_PORT, DEFAULT_PORT);
        if (port <= 0) {
            return Optional.empty();
        }
        Path tokenFile = tokenFile(workDir);
        String configuredToken = environment("HS_ADMIN_TOKEN", null);
        if (configuredToken != null && !configuredToken.isBlank()) {
            return Optional.of(new AdminSettings(InetAddress.getByName(LOOPBACK), port,
                    configuredToken.trim(), tokenFile));
        }
        if (!Files.isReadable(tokenFile)) {
            return Optional.empty();
        }
        String stored = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AdminSettings(InetAddress.getByName(LOOPBACK), port, stored, tokenFile));
    }

    /** The port the API would use, without touching the token. */
    public static int configuredPort() {
        return intFromEnvironment(ENV_PORT, DEFAULT_PORT);
    }

    private static Path tokenFile(Path workDir) {
        return Path.of(environment("HS_ADMIN_TOKEN_FILE",
                workDir.resolve(DEFAULT_TOKEN_FILE).toString())).toAbsolutePath();
    }

    /** True when the token came from a file this process can hand to an operator. */
    public boolean tokenIsInFile() {
        return Files.isReadable(this.tokenFile);
    }

    /** Never print the token itself; this is what goes into a log line. */
    public String describe() {
        return this.bindAddress.getHostAddress() + ":" + this.port + " token=" + this.tokenFile;
    }

    private static String loadOrCreateToken(Path file) throws IOException {
        if (Files.isReadable(file)) {
            String existing = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (!existing.isEmpty()) {
                return existing;
            }
        }
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        String generated = generateToken();
        Files.writeString(file, generated + System.lineSeparator(), StandardCharsets.UTF_8);
        narrowToOwner(file);
        return generated;
    }

    /**
     * Seeded once for the process: a fresh {@code SecureRandom} per token re-seeds from the
     * platform entropy source every time, which is waste at best and a block at worst
     * (java:S2119).
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void narrowToOwner(Path file) throws IOException {
        if (!Files.getFileStore(file).supportsFileAttributeView("posix")) {
            return;
        }
        Set<PosixFilePermission> ownerOnly = EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        Files.setPosixFilePermissions(file, ownerOnly);
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            value = System.getProperty(name);
        }
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int intFromEnvironment(String name, int fallback) {
        String value = environment(name, null);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " is not a number: " + value, e);
        }
    }
}
