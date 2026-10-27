package com.hushsign.agent.runtime.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * P7-S4: where the token comes from and what happens when there is none.
 *
 * <p>The important assertion is the negative one — a missing token must never silently mean
 * "no authentication".
 */
class AdminSettingsTest {

    @AfterEach
    void clearOverrides() {
        System.clearProperty("HS_ADMIN_PORT");
        System.clearProperty("HS_ADMIN_TOKEN");
        System.clearProperty("HS_ADMIN_TOKEN_FILE");
    }

    @Test
    void resolveCreatesATokenOnceAndKeepsUsingIt(@org.junit.jupiter.api.io.TempDir Path workDir)
            throws IOException {
        Optional<AdminSettings> first = AdminSettings.resolve(workDir);

        assertTrue(first.isPresent());
        Path tokenFile = workDir.resolve(AdminSettings.DEFAULT_TOKEN_FILE);
        assertTrue(Files.isReadable(tokenFile), "the agent must leave the token where the operator looks");
        assertEquals(first.get().token(), Files.readString(tokenFile, StandardCharsets.UTF_8).trim());
        assertTrue(first.get().token().length() >= 40, "a 32-byte token, base64url encoded");

        Optional<AdminSettings> second = AdminSettings.resolve(workDir);
        assertEquals(first.get().token(), second.orElseThrow().token(), "a restart keeps the same token");
    }

    @Test
    void locateNeverCreatesAToken(@org.junit.jupiter.api.io.TempDir Path workDir) throws IOException {
        assertTrue(AdminSettings.locate(workDir).isEmpty(), "the client must not create the secret");
        assertFalse(Files.exists(workDir.resolve(AdminSettings.DEFAULT_TOKEN_FILE)));
    }

    @Test
    void locateFindsTheTokenTheAgentWrote(@org.junit.jupiter.api.io.TempDir Path workDir)
            throws IOException {
        AdminSettings resolved = AdminSettings.resolve(workDir).orElseThrow();

        AdminSettings located = AdminSettings.locate(workDir).orElseThrow();

        assertEquals(resolved.token(), located.token());
        assertEquals(resolved.port(), located.port());
    }

    @Test
    void theEnvironmentTokenWins(@org.junit.jupiter.api.io.TempDir Path workDir) throws IOException {
        System.setProperty("HS_ADMIN_TOKEN", "a-token-from-the-deployment");

        AdminSettings settings = AdminSettings.resolve(workDir).orElseThrow();

        assertEquals("a-token-from-the-deployment", settings.token());
        assertFalse(Files.exists(workDir.resolve(AdminSettings.DEFAULT_TOKEN_FILE)),
                "nothing is written when the deployment supplies the token");
    }

    @Test
    void portZeroSwitchesTheApiOff(@org.junit.jupiter.api.io.TempDir Path workDir) throws IOException {
        System.setProperty("HS_ADMIN_PORT", "0");

        assertTrue(AdminSettings.resolve(workDir).isEmpty());
        assertTrue(AdminSettings.locate(workDir).isEmpty());
    }

    @Test
    void theListenerIsLoopbackOnly(@org.junit.jupiter.api.io.TempDir Path workDir) throws IOException {
        AdminSettings settings = AdminSettings.resolve(workDir).orElseThrow();

        assertNotNull(settings.bindAddress());
        assertEquals("127.0.0.1", settings.bindAddress().getHostAddress());
    }

    @Test
    void theTokenIsNeverInTheDescription(@org.junit.jupiter.api.io.TempDir Path workDir) throws IOException {
        AdminSettings settings = AdminSettings.resolve(workDir).orElseThrow();

        assertFalse(settings.describe().contains(settings.token()), "this string goes into a log line");
    }
}
