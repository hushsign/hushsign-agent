package com.hushsign.agent.runtime.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P7-S4: the operator's half of the API, run against a real server.
 *
 * <p>The exit code matters as much as the output: it is what a wrapper script branches on, so
 * "the agent said no" and "you typed it wrong" have to be distinguishable.
 */
class AdminClientTest {

    private static final String TOKEN = "client-token-abcdefghij";

    @TempDir
    Path workDir;

    @AfterEach
    void clearPort() {
        System.clearProperty("HS_ADMIN_PORT");
    }

    @Test
    void statusPrintsTheAgentsAnswer() throws Exception {
        AdminServer server = startServer(TOKEN);
        try (server) {
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();

            int exit = AdminClient.run(new String[]{"status"}, this.workDir, out, err);

            assertEquals(0, exit, err.toString());
            assertTrue(out.toString().contains("gw-1"));
        }
    }

    @Test
    void policyReloadIsOneCommand() throws Exception {
        AdminServer server = startServer(TOKEN);
        try (server) {
            StringBuilder out = new StringBuilder();

            int exit = AdminClient.run(new String[]{"policy", "reload"}, this.workDir, out, new StringBuilder());

            assertEquals(0, exit);
            assertTrue(out.toString().contains("applied"));
        }
    }

    @Test
    void pauseAndResumeRoundTrip() throws Exception {
        AdminServer server = startServer(TOKEN);
        try (server) {
            StringBuilder out = new StringBuilder();

            assertEquals(0, AdminClient.run(new String[]{"pause"}, this.workDir, out, new StringBuilder()));
            assertTrue(out.toString().contains("\"paused\":true"));

            out.setLength(0);
            assertEquals(0, AdminClient.run(new String[]{"resume"}, this.workDir, out, new StringBuilder()));
            assertTrue(out.toString().contains("\"paused\":false"));
        }
    }

    @Test
    void aRefusedRequestIsAFailureExit() throws Exception {
        AdminServer server = startServer(TOKEN);
        try (server) {
            Files.writeString(this.workDir.resolve(AdminSettings.DEFAULT_TOKEN_FILE), "a-different-token\n",
                    StandardCharsets.UTF_8);
            StringBuilder err = new StringBuilder();

            int exit = AdminClient.run(new String[]{"status"}, this.workDir, new StringBuilder(), err);

            assertEquals(1, exit);
            assertTrue(err.toString().contains("401"), err.toString());
        }
    }

    @Test
    void withoutATokenThereIsNothingToAuthenticateWith() {
        StringBuilder err = new StringBuilder();

        int exit = AdminClient.run(new String[]{"status"}, this.workDir, new StringBuilder(), err);

        assertEquals(1, exit);
        assertTrue(err.toString().contains("no admin token"));
    }

    @Test
    void anUnreachableAgentIsAFailureNotACrash() throws Exception {
        Files.writeString(this.workDir.resolve(AdminSettings.DEFAULT_TOKEN_FILE), TOKEN, StandardCharsets.UTF_8);
        System.setProperty("HS_ADMIN_PORT", "1");
        StringBuilder err = new StringBuilder();

        int exit = AdminClient.run(new String[]{"status"}, this.workDir, new StringBuilder(), err);

        assertEquals(1, exit);
        assertTrue(err.toString().contains("cannot reach the agent"));
    }

    @Test
    void anUnknownCommandIsAUsageError() {
        StringBuilder err = new StringBuilder();

        int exit = AdminClient.run(new String[]{"explode"}, this.workDir, new StringBuilder(), err);

        assertEquals(2, exit, "a typo must not look like a gateway failure");
        assertTrue(err.toString().contains("usage:"));
    }

    private AdminServer startServer(String token) throws Exception {
        AdminState state = new AdminState();
        AdminRuntime runtime = new AdminRuntime() {
            @Override
            public AdminStatus status() {
                return new AdminStatus("gw-1", "test", 1, state.paused(), state.killed(), false,
                        List.of(), "policy.yaml", "agent.yaml", "p", "c", 0, 0, 0, 0, true, 1, "audit");
            }

            @Override
            public List<ModemStatus> modems() {
                return List.of();
            }

            @Override
            public PolicyReload reloadPolicy() {
                return PolicyReload.accepted("reloaded");
            }

            @Override
            public boolean pause() {
                return state.pause();
            }

            @Override
            public boolean resume() {
                return state.resume();
            }

            @Override
            public boolean kill() {
                return state.kill();
            }
        };
        AdminServer server = AdminServer.start(
                new AdminSettings(java.net.InetAddress.getByName("127.0.0.1"), 0, token,
                        this.workDir.resolve(AdminSettings.DEFAULT_TOKEN_FILE)),
                runtime, com.hushsign.agent.runtime.audit.NoopAuditLog.INSTANCE);
        System.setProperty("HS_ADMIN_PORT", String.valueOf(server.port()));
        Files.writeString(this.workDir.resolve(AdminSettings.DEFAULT_TOKEN_FILE), token + "\n",
                StandardCharsets.UTF_8);
        return server;
    }
}
