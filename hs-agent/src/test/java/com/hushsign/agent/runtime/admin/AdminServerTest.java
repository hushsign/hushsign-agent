package com.hushsign.agent.runtime.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hushsign.agent.runtime.audit.AuditEntry;
import com.hushsign.agent.runtime.audit.FileAuditChain;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P7-S4: the HTTP surface, exercised over a real socket rather than by calling the handlers.
 *
 * <p>The audit assertions use the real {@link FileAuditChain}, so "an admin action is recorded"
 * means the entry is in the hash chain the offline CLI verifies, not that a fake was called.
 */
class AdminServerTest {

    private static final String TOKEN = "test-token-0123456789";

    @TempDir
    Path workDir;

    private FileAuditChain audit;
    private RecordingRuntime runtime;
    private AdminServer server;
    private HttpClient client;

    @BeforeEach
    void startServer() throws IOException {
        this.audit = FileAuditChain.open(this.workDir.resolve("audit"), "gw-1", 0, Clock.systemUTC());
        this.runtime = new RecordingRuntime();
        AdminSettings settings = new AdminSettings(
                InetAddress.getByName("127.0.0.1"), 0, TOKEN, this.workDir.resolve("admin.token"));
        this.server = AdminServer.start(settings, this.runtime, this.audit);
        this.client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stopServer() {
        this.server.close();
    }

    @Test
    void aRequestWithoutATokenIsRefusedAndRecorded() throws Exception {
        HttpResponse<String> response = send("GET", "/status", null);

        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("bearer"));
        assertEquals(0, this.runtime.statusCalls, "an unauthenticated caller must not reach the runtime");
        assertTrue(adminEntries().stream().anyMatch(entry -> "ADMIN_DENIED".equals(entry.type())));
    }

    @Test
    void aRequestWithTheWrongTokenIsRefused() throws Exception {
        HttpResponse<String> response = send("GET", "/status", "Bearer not-the-token");

        assertEquals(401, response.statusCode());
        assertEquals(0, this.runtime.statusCalls);
    }

    @Test
    void statusIsReturnedWithTheToken() throws Exception {
        HttpResponse<String> response = send("GET", "/status", "Bearer " + TOKEN);

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));
        assertTrue(response.body().contains("gw-1"));
        assertEquals(1, this.runtime.statusCalls);
    }

    @Test
    void modemsAreReturnedWithTheToken() throws Exception {
        HttpResponse<String> response = send("GET", "/modems", "Bearer " + TOKEN);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("31015**********"), "the admin API reports a masked IMSI");
        assertFalse(response.body().contains("310150123456789"));
    }

    @Test
    void aMutationEndpointRefusesGet() throws Exception {
        HttpResponse<String> response = send("GET", "/pause", "Bearer " + TOKEN);

        assertEquals(405, response.statusCode());
        assertFalse(this.runtime.paused, "a GET must not pause anything");
    }

    @Test
    void pauseIsRecordedInTheAuditChain() throws Exception {
        HttpResponse<String> response = send("POST", "/pause", "Bearer " + TOKEN);

        assertEquals(200, response.statusCode());
        assertTrue(this.runtime.paused);
        assertTrue(response.body().contains("\"changed\":true"));
        assertTrue(adminEntries().stream().anyMatch(entry ->
                        "ADMIN_COMMAND".equals(entry.type()) && entry.detail().startsWith("pause")),
                "who paused the gateway is the first question after an incident");
        assertTrue(this.audit.verify().ok(), "the audit chain still verifies after an admin action");
    }

    @Test
    void pausingTwiceRecordsOneAction() throws Exception {
        send("POST", "/pause", "Bearer " + TOKEN);
        HttpResponse<String> second = send("POST", "/pause", "Bearer " + TOKEN);

        assertTrue(second.body().contains("\"changed\":false"));
        assertEquals(1, adminEntries().stream()
                .filter(entry -> "ADMIN_COMMAND".equals(entry.type())).count());
    }

    @Test
    void resumeAndKillReportTheState() throws Exception {
        send("POST", "/pause", "Bearer " + TOKEN);

        HttpResponse<String> resumed = send("POST", "/resume", "Bearer " + TOKEN);

        assertTrue(resumed.body().contains("\"paused\":false"));
        assertFalse(this.runtime.paused);

        HttpResponse<String> killed = send("POST", "/kill", "Bearer " + TOKEN);

        assertTrue(killed.body().contains("\"killed\":true"));
        assertTrue(this.runtime.killed);
    }

    @Test
    void aRefusedReloadIsReportedAsRefused() throws Exception {
        this.runtime.reloadResult = AdminRuntime.PolicyReload.refused("unknown key 'quietHour'");

        HttpResponse<String> response = send("POST", "/policy/reload", "Bearer " + TOKEN);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"applied\":false"));
        assertTrue(response.body().contains("quietHour"));
        assertTrue(adminEntries().stream().anyMatch(entry -> "POLICY_RELOAD".equals(entry.type())
                && entry.detail().startsWith("refused")));
    }

    @Test
    void anUnknownEndpointIsNotFound() throws Exception {
        assertEquals(404, send("GET", "/shell", "Bearer " + TOKEN).statusCode());
    }

    @Test
    void aNonLoopbackBindIsRefused() throws Exception {
        AdminSettings publicSettings = new AdminSettings(
                InetAddress.getByName("0.0.0.0"), 0, TOKEN, this.workDir.resolve("t"));

        assertThrows(IllegalArgumentException.class,
                () -> AdminServer.start(publicSettings, this.runtime, this.audit));
    }

    private List<AuditEntry> adminEntries() {
        return this.audit.tail(50).stream()
                .filter(entry -> entry.type().startsWith("ADMIN_") || "POLICY_RELOAD".equals(entry.type()))
                .toList();
    }

    private HttpResponse<String> send(String method, String path, String authorization)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(this.server.baseUrl() + path));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        request.method(method, HttpRequest.BodyPublishers.noBody());
        return this.client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A runtime that remembers what was asked of it and answers with fixed data. */
    private static final class RecordingRuntime implements AdminRuntime {

        private final AdminState state = new AdminState();
        private int statusCalls;
        private boolean paused;
        private boolean killed;
        private PolicyReload reloadResult = PolicyReload.accepted("nothing to change");

        @Override
        public AdminStatus status() {
            this.statusCalls++;
            return new AdminStatus("gw-1", "test", 12, this.state.paused(), this.state.killed(),
                    false, List.of("SILENT_TP0"), "/opt/hs/policy.yaml", "/opt/hs/agent.yaml",
                    "policyhash", "confighash", 0, 0, 0, 0, true, 4, "/opt/hs/audit");
        }

        @Override
        public List<ModemStatus> modems() {
            return List.of(new ModemStatus("/dev/ttyUSB1", "READY", "31015**********",
                    "Quectel", "EC25", "ABC123", 18));
        }

        @Override
        public PolicyReload reloadPolicy() {
            return this.reloadResult;
        }

        @Override
        public boolean pause() {
            this.paused = true;
            return this.state.pause();
        }

        @Override
        public boolean resume() {
            this.paused = false;
            return this.state.resume();
        }

        @Override
        public boolean kill() {
            this.killed = true;
            return this.state.kill();
        }
    }
}
