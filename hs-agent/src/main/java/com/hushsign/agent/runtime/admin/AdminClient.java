package com.hushsign.agent.runtime.admin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * {@code hs-agent ctl ...} — the operator's end of the admin API (P7-S4).
 *
 * <p>Deliberately tiny and dependency-free: it resolves the same token the agent resolved, sends
 * one request, prints the body and exits with a code a script can branch on. Exit 0 means the
 * agent answered 2xx; 1 means it refused or could not be reached; 2 means the command itself was
 * wrong, so a wrapper script can tell "you typed it wrong" from "the gateway is unhappy".
 */
public final class AdminClient {

    static final int OK = 0;
    static final int FAILED = 1;
    static final int USAGE = 2;

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private AdminClient() {
    }

    /** Runs one command; never throws, always returns an exit code. */
    public static int run(String[] args, Path workDir, Appendable out, Appendable err) {
        if (args.length == 0) {
            printUsage(err);
            return USAGE;
        }
        Verb verb = Verb.parse(args);
        if (verb == null) {
            printUsage(err);
            return USAGE;
        }
        try {
            Optional<AdminSettings> settings = AdminSettings.locate(workDir);
            if (settings.isEmpty()) {
                err.append("no admin token at ").append(workDir.resolve(AdminSettings.DEFAULT_TOKEN_FILE).toString())
                        .append(" and HS_ADMIN_TOKEN is not set; is the agent running?\n");
                return FAILED;
            }
            AdminSettings resolved = settings.get();
            HttpResponse<String> response = send(resolved, verb);
            out.append(response.body()).append('\n');
            if (response.statusCode() / 100 == 2) {
                return OK;
            }
            err.append("agent refused: HTTP ").append(Integer.toString(response.statusCode())).append('\n');
            return FAILED;
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            try {
                err.append("cannot reach the agent: ").append(describe(e, resolvedPortOrZero()))
                        .append('\n');
            } catch (IOException _) {
                // Nothing useful left to do with a broken stderr.
            }
            return FAILED;
        }
    }

    /** A ConnectException carries no message, and "null" tells an operator nothing. */
    private static String describe(Exception e, int port) {
        if (e instanceof java.net.ConnectException) {
            return "connection refused on 127.0.0.1:" + port + " (is hs-agent running?)";
        }
        if (e instanceof java.net.http.HttpTimeoutException) {
            return "the agent did not answer within " + TIMEOUT.toSeconds() + "s";
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static int resolvedPortOrZero() {
        try {
            return AdminSettings.configuredPort();
        } catch (RuntimeException _) {
            return 0;
        }
    }

    private static HttpResponse<String> send(AdminSettings settings, Verb verb)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + settings.port() + verb.path))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + settings.token())
                .header("Accept", "application/json");
        if (verb.post) {
            request.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            request.GET();
        }
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static void printUsage(Appendable err) {
        String usage = """
                usage: hs-agent ctl <command>

                  status            gateway, uptime, spool depth, audit verdict, flags
                  modems            the claimed modem, with a masked IMSI
                  pause             stop sending; the agent stays up and keeps spooling
                  resume            undo a pause
                  kill              engage the kill switch; only a restart clears it
                  policy reload     re-read policy.yaml without a restart
                """;
        try {
            err.append(usage);
        } catch (IOException _) {
            // Nothing useful left to do with a broken stderr.
        }
    }

    /** The five things an operator can ask for, and how each one is addressed. */
    private record Verb(String path, boolean post) {

        static Verb parse(String[] args) {
            return switch (String.join(" ", args).trim()) {
                case "status" -> new Verb("/status", false);
                case "modems" -> new Verb("/modems", false);
                case "pause" -> new Verb("/pause", true);
                case "resume" -> new Verb("/resume", true);
                case "kill" -> new Verb("/kill", true);
                case "policy reload", "reload" -> new Verb("/policy/reload", true);
                default -> null;
            };
        }
    }
}
