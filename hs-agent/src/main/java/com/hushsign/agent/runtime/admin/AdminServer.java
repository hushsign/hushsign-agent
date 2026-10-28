package com.hushsign.agent.runtime.admin;

import tools.jackson.databind.ObjectMapper;
import com.hushsign.agent.runtime.audit.AuditLog;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The local control surface (P7-S4): {@code status}, {@code modems}, {@code policy reload},
 * {@code pause}, {@code resume} and {@code kill} over HTTP on loopback.
 *
 * <p>Why an HTTP port and not a shell: the agent runs under systemd on a gateway with no
 * operator logged in, so there has to be a way to ask "are you healthy" and to stop it sending
 * without an ssh session and a restart. Bound to {@code 127.0.0.1} with a bearer token, it is
 * reachable only by a process already on the device.
 *
 * <p>The risk that comes with it, stated plainly: <b>any local process that can read the token
 * file can pause, kill or reload the agent.</b> That is why the file is {@code 0600}, why every
 * mutation lands in the audit chain together with the caller's address, and why a bad token is
 * itself recorded.
 */
public final class AdminServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AdminServer.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final long DENIAL_AUDIT_INTERVAL_MS = 60_000;

    private final HttpServer server;
    /** The one field every error body carries; the local client reads it by this name. */
    private static final String JSON_ERROR = "error";

    private final ExecutorService executor;
    private final AdminRuntime runtime;
    private final AuditLog audit;
    private final byte[] expectedAuthorization;
    private final AtomicLong lastDenialAuditAt = new AtomicLong();

    private AdminServer(HttpServer server, ExecutorService executor, AdminSettings settings,
                        AdminRuntime runtime, AuditLog audit) {
        this.server = server;
        this.executor = executor;
        this.runtime = runtime;
        this.audit = audit;
        this.expectedAuthorization = ("Bearer " + settings.token()).getBytes(StandardCharsets.UTF_8);
    }

    public static AdminServer start(AdminSettings settings, AdminRuntime runtime, AuditLog audit)
            throws IOException {
        requireLoopback(settings);
        HttpServer server = HttpServer.create(
                new InetSocketAddress(settings.bindAddress(), settings.port()), 0);
        ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "hs-agent-admin");
            thread.setDaemon(true);
            return thread;
        });
        AdminServer admin = new AdminServer(server, executor, settings, runtime, audit);
        server.createContext("/", admin::dispatch);
        server.setExecutor(executor);
        server.start();
        if (LOG.isInfoEnabled()) {
            LOG.info("admin API listening on {}", settings.describe());
        }
        return admin;
    }

    /**
     * Refuses to start on anything but loopback. The token protects the endpoint; the binding is
     * what keeps it off the wire in the first place, and a configuration mistake must not be able
     * to undo that.
     */
    private static void requireLoopback(AdminSettings settings) {
        if (!settings.bindAddress().isLoopbackAddress()) {
            throw new IllegalArgumentException("the admin API only binds to loopback, not "
                    + settings.bindAddress().getHostAddress());
        }
    }

    public int port() {
        return this.server.getAddress().getPort();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    private void dispatch(HttpExchange exchange) {
        try {
            if (!authorized(exchange)) {
                deny(exchange);
                return;
            }
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            switch (path) {
                case "/status" -> read(method, exchange, this.runtime::status);
                case "/modems" -> read(method, exchange, this.runtime::modems);
                case "/policy/reload" -> mutate(method, exchange, this::reload);
                case "/pause" -> mutate(method, exchange, () -> changed(this.runtime.pause(), "pause", exchange));
                case "/resume" -> mutate(method, exchange, () -> changed(this.runtime.resume(), "resume", exchange));
                case "/kill" -> mutate(method, exchange, () -> changed(this.runtime.kill(), "kill", exchange));
                default -> send(exchange, 404, Map.of(JSON_ERROR, "no such endpoint: " + path));
            }
        } catch (IOException e) {
            LOG.warn("admin request failed: {}", e.toString());
        } catch (RuntimeException e) {
            LOG.warn("admin request failed: {}", e.toString());
            sendQuietly(exchange, 500, Map.of(JSON_ERROR, String.valueOf(e.getMessage())));
        } finally {
            exchange.close();
        }
    }

    private Object reload() {
        AdminRuntime.PolicyReload reload = this.runtime.reloadPolicy();
        this.audit.append("POLICY_RELOAD", null,
                (reload.applied() ? "applied: " : "refused: ") + reload.detail());
        return reload;
    }

    private Object changed(boolean changed, String action, HttpExchange exchange) {
        if (changed) {
            // The address is the whole value of this line: it answers "who told the gateway to
            // stop sending", which is the first question after an incident.
            this.audit.append("ADMIN_COMMAND", null, action + " from " + exchange.getRemoteAddress());
            LOG.warn("admin {} from {}", action, exchange.getRemoteAddress());
        }
        AdminStatus status = this.runtime.status();
        return Map.of("changed", changed, "paused", status.paused(), "killed", status.killed());
    }

    private interface Body {
        Object value();
    }

    private void read(String method, HttpExchange exchange, Body body) throws IOException {
        if (!"GET".equals(method)) {
            send(exchange, 405, Map.of(JSON_ERROR, "use GET"));
            return;
        }
        send(exchange, 200, body.value());
    }

    private void mutate(String method, HttpExchange exchange, Body body) throws IOException {
        if (!"POST".equals(method)) {
            send(exchange, 405, Map.of(JSON_ERROR, "use POST"));
            return;
        }
        send(exchange, 200, body.value());
    }

    private boolean authorized(HttpExchange exchange) {
        List<String> headers = exchange.getRequestHeaders().get("Authorization");
        if (headers == null || headers.isEmpty()) {
            return false;
        }
        // Constant-time: a byte-at-a-time comparison leaks the token to anyone who can measure
        // the responses, which on a shared box is anyone local.
        return MessageDigest.isEqual(this.expectedAuthorization,
                headers.get(0).getBytes(StandardCharsets.UTF_8));
    }

    private void deny(HttpExchange exchange) throws IOException {
        long now = System.currentTimeMillis();
        long last = this.lastDenialAuditAt.get();
        if (now - last > DENIAL_AUDIT_INTERVAL_MS && this.lastDenialAuditAt.compareAndSet(last, now)) {
            // Rate limited: an unauthenticated caller must not be able to fill the audit chain,
            // but a refusal is worth having on record.
            this.audit.append("ADMIN_DENIED", null,
                    "bad or missing token from " + exchange.getRemoteAddress());
        }
        LOG.warn("admin request refused from {}: bad or missing token", exchange.getRemoteAddress());
        send(exchange, 401, Map.of(JSON_ERROR, "a bearer token is required"));
    }

    private void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private void sendQuietly(HttpExchange exchange, int status, Object body) {
        try {
            send(exchange, status, body);
        } catch (IOException | RuntimeException _) {
            // The client went away or the exchange is already spent; nothing left to tell it.
        }
    }

    @Override
    public void close() {
        this.server.stop(0);
        this.executor.shutdownNow();
        LOG.info("admin API stopped");
    }
}
