package com.hushsign.agent;

import com.hushsign.agent.config.AgentConfig;
import com.hushsign.agent.config.AgentConfigLoader;
import com.hushsign.agent.config.ConfigException;
import com.hushsign.agent.gsm.modem.Modem;
import com.hushsign.agent.gsm.modem.ModemConfig;
import com.hushsign.agent.gsm.modem.ModemException;
import com.hushsign.agent.gsm.modem.ModemState;
import com.hushsign.agent.gsm.modem.ModemScanner;
import com.hushsign.agent.gsm.modem.PduSubmission;
import com.hushsign.agent.gsm.serial.JSerialCommPortFactory;
import com.hushsign.agent.gsm.serial.SerialPortFactory;
import com.hushsign.agent.runtime.AgentRuntime;
import com.hushsign.agent.runtime.admin.AdminClient;
import com.hushsign.agent.runtime.execution.RecoveringPduSender;
import com.hushsign.agent.runtime.admin.AdminServer;
import com.hushsign.agent.runtime.admin.AdminSettings;
import com.hushsign.agent.runtime.sensitive.IdentifierLogging;
import com.hushsign.agent.runtime.sensitive.LogDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Entry point of the edge agent (P1-S13): load {@code agent.yaml}, claim one
 * modem, run {@link AgentRuntime} until the JVM is asked to stop.
 *
 * <p>Usage: {@code hs-agent [--config <path>]} (default {@code agent.yaml}).
 * Secrets never come from the file: {@code HS_KAFKA_SECURITY_SASL_PASSWORD} and
 * {@code HS_MODEMS_SIM_PIN} are read from the environment by the loader.
 */
public final class AgentMain {

    private static final Logger LOG = LoggerFactory.getLogger(AgentMain.class);

    /** A recovery attempt is impatient on purpose; see {@link #recoveryModemConfig}. */
    private static final int RECOVERY_RESPONSE_TIMEOUT_MS = 8_000;
    private static final int RECOVERY_PROBE_ATTEMPTS = 2;

    private AgentMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "ctl".equals(args[0])) {
            // The client half of P7-S4: same binary, so an operator on the gateway has the tool
            // without a second artifact to install. The token lives in the agent's work
            // directory, which HS_AGENT_WORKDIR points at (install.sh's launcher sets
            // /var/lib/hs-agent); without that, `ctl` looked in whatever directory the operator
            // happened to be standing in and reported "no admin token" from there.
            String configuredWorkDir = System.getenv("HS_AGENT_WORKDIR");
            Path workDir = configuredWorkDir == null || configuredWorkDir.isBlank()
                    ? Path.of(".").toAbsolutePath()
                    : Path.of(configuredWorkDir).toAbsolutePath();
            System.exit(AdminClient.run(Arrays.copyOfRange(args, 1, args.length),
                    workDir, System.out, System.err));
        }
        try {
            run(args);
        } catch (ConfigException e) {
            // A configuration error is an operator's problem, not a bug, and the stack trace for
            // it says nothing an operator can act on. One line, and a distinct exit code so a
            // wrapper or systemd unit can tell it apart from a crash.
            LOG.error("{}", e.getMessage());
            System.exit(2);
        } catch (ModemException e) {
            // No modem is an operational condition, not a crash - but the process has to end so
            // systemd's Restart=always can try again. It used to propagate out of main() and the
            // JVM stayed up on a non-daemon serial reader thread: the unit read "active (running)"
            // for an agent whose main thread was already dead, and the gateway quietly sent
            // nothing until somebody looked. A gateway boots before its dongle often enough that
            // this is the normal path after a reboot.
            LOG.error("the agent cannot start: {}", e.getMessage());
            System.exit(3);
        }
    }

    private static void run(String[] args) throws Exception {
        if (!LogDirectory.harden(LogDirectory.configured())) {
            LOG.info("log directory is not owner-only; keep the log directory on the device");
        }
        boolean rawIdentifiers = rawIdentifiersEnabled();
        if (rawIdentifiers) {
            // Loud on purpose: somebody chose this, and the choice has to be findable in the
            // log of the device that made it.
            LOG.warn("{}={}: {} carries IMSI / MSISDN / ICCID in clear; keep it on the device "
                            + "and turn it off when the investigation is over",
                    IdentifierLogging.ENV, IdentifierLogging.RAW, LogDirectory.configured().resolve("agent.log"));
        }
        Path configFile = configPath(args);
        AgentConfig config = AgentConfigLoader.load(configFile);
        LOG.info("starting agent gateway={} operators={} pduSubmission={}",
                config.gatewayId(), config.operators(), config.modems().pduSubmission());

        SerialPortFactory factory = new JSerialCommPortFactory();
        Modem modem = claimModem(config, factory);
        if (LOG.isInfoEnabled()) {
            LOG.info("modem claimed: port={} state={}", modem.portName(), modem.state());
        }

        // A dongle re-enumerates on a hub reset or its own reboot, and the descriptor the agent
        // opened dies with it. The configured /dev/serial/by-id/ path is what survives that, so it
        // is re-resolved here rather than only at startup: without this the agent kept writing to
        // a port that no longer existed until someone restarted the service.
        AtomicReference<Modem> claimed = new AtomicReference<>(modem);
        RecoveringPduSender sender = new RecoveringPduSender(
                pdu -> claimed.get().sendPdu(pdu),
                () -> {
                    Modem fresh = reopenModem(config, factory, claimed);
                    return fresh::sendPdu;
                },
                () -> claimed.get().state() == ModemState.READY,
                () -> claimed.get().ping());

        // The executor retries a failed send, but only when a command reaches the modem - and the
        // policy gate sits before that, so a capped or idle gateway never notices a port that died.
        // The sender's own check is the independent reason to look; the runtime only supplies the
        // clock, because the check has to run under the same lock as a send.
        AgentRuntime runtime = AgentRuntime.start(config, configFile, modem, sender, claimed::get,
                sender::recoverIfUnhealthy,
                version(AgentMain.class.getPackage().getImplementationVersion()));
        if (rawIdentifiers) {
            runtime.auditLog().append("STARTUP", null,
                    "log identifiers = raw: agent.log carries subscriber identifiers in clear");
        }
        Optional<AdminServer> adminServer = startAdminServer(config, runtime);
        try {
            runtime.awaitTermination();
        } finally {
            adminServer.ifPresent(AdminServer::close);
            runtime.close();
        }
    }

    /**
     * Whether raw identifiers may be logged (P7-S12), reported as a configuration error rather
     * than a stack trace: a typo in the variable name must not be silently read as "masked".
     */
    private static boolean rawIdentifiersEnabled() {
        try {
            return IdentifierLogging.RAW.equals(IdentifierLogging.configured());
        } catch (IllegalArgumentException e) {
            throw new ConfigException(e.getMessage());
        }
    }

    /**
     * Brings up the local control surface (P7-S4). A failure here is reported but does not stop
     * the agent: losing the ability to pause is an operational problem, while refusing to send
     * because a port was busy would be an outage. The line is logged at ERROR so it cannot be
     * missed in a startup log.
     */
    private static Optional<AdminServer> startAdminServer(AgentConfig config, AgentRuntime runtime) {
        try {
            Optional<AdminSettings> settings =
                    AdminSettings.resolve(Path.of(config.workDir()).toAbsolutePath());
            if (settings.isEmpty()) {
                LOG.info("admin API disabled (HS_ADMIN_PORT={})", AdminSettings.configuredPort());
                return Optional.empty();
            }
            return Optional.of(AdminServer.start(settings.get(), runtime, runtime.auditLog()));
        } catch (IOException | RuntimeException e) {
            LOG.error("admin API not started, the agent keeps running: {}", e.toString());
            return Optional.empty();
        }
    }

    private static Path configPath(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--config".equals(args[i])) {
                return Path.of(args[i + 1]);
            }
        }
        return Path.of("agent.yaml");
    }

    /**
     * Claims the first modem: configured ports first (fail-fast on the first one
     * that answers), otherwise a scan. Scanning probes only, so the winning port
     * is reopened with the full init (SIM/PIN, registration, PDU mode) — a modem
     * that cannot read its SIM must never look READY (P1-S14).
     */
    static Modem claimModem(AgentConfig config, SerialPortFactory factory) throws ModemException {
        return claimModem(config, factory, modemConfig(config));
    }

    static Modem claimModem(AgentConfig config, SerialPortFactory factory, ModemConfig modemConfig)
            throws ModemException {
        Objects.requireNonNull(modemConfig, "modemConfig");
        if (!config.modems().autoScan()) {
            for (String port : config.modems().ports()) {
                try {
                    return Modem.open(factory.open(port), modemConfig);
                } catch (ModemException | RuntimeException e) {
                    // A port that no longer exists fails here as an unchecked error (the factory
                    // cannot open it), and it used to take the whole claim down with it: the
                    // second configured port was never tried, and the operator saw an
                    // IllegalArgumentException instead of "no modem on this gateway".
                    LOG.warn("no usable modem on {}: {}", port, e.getMessage());
                }
            }
            throw new ModemException(noConfiguredPortAnswered(config.modems().ports(), factory.listPorts()));
        }

        String portName = new ModemScanner(factory, modemConfig).scanFirst()
                .map(modem -> {
                    String name = modem.portName();
                    closeQuietly(modem);
                    return name;
                })
                .orElseThrow(() -> new ModemException("no modem found while scanning serial ports"));
        LOG.info("scan found a modem on {}; running the full init", portName);
        return Modem.open(factory.open(portName), modemConfig);
    }

    /**
     * Says which ports the machine does have, not only which ones the file asked for. Without
     * this the operator reading the journal sees "/dev/ttyUSB0" and has no way to know the dongle
     * simply came back as /dev/ttyUSB1 after a replug.
     */
    static String noConfiguredPortAnswered(List<String> configured, List<String> present) {
        String message = "none of the configured ports answered: " + configured
                + "; serial ports present: " + present
                // Saying which ports the machine has is only half the story, and the half that
                // reads as "but they are right there". A by-id link is a pointer: it can be absent
                // while the device is on the bus, or dangle at a ttyUSB name that this process
                // still holds open. Naming what the configured path actually is saves the next
                // person the twenty minutes it cost here.
                + configuredState(configured);
        if (configured.stream().anyMatch(AgentMain::isEnumerationOrderPath)) {
            message += ". A /dev/ttyUSB<n> name is an enumeration order, not a device: pin the"
                    + " /dev/serial/by-id/ path instead and it survives the next replug";
        }
        return message;
    }

    /** What each configured path resolves to right now, so a failure says which kind it was. */
    private static String configuredState(List<String> configured) {
        StringBuilder state = new StringBuilder();
        for (String port : configured) {
            Path path = Path.of(port);
            state.append("; ").append(port);
            if (!Files.exists(path)) {
                state.append(" does not exist right now");
                continue;
            }
            try {
                Path resolved = path.toRealPath();
                state.append(" -> ").append(resolved.getFileName());
                state.append(Files.isReadable(resolved) ? " (readable)" : " (not readable)");
            } catch (IOException e) {
                state.append(" cannot be resolved: ").append(e.getMessage());
            }
        }
        return state.toString();
    }

    private static boolean isEnumerationOrderPath(String port) {
        return port.matches("/dev/tty(USB|ACM)\\d+");
    }

    /**
     * The profile used when re-claiming a modem that has already failed.
     *
     * <p>The steady-state profile waits 30 s per command and tries three times, which is right for
     * a modem that is expected to answer and wrong for a port that has just vanished: the send
     * path is the only consumer, so a recovery attempt there blocks every queued command behind
     * it. On 2026-11-02 one unplug stalled the agent for 87 s while the platform kept emitting a
     * command every 10 s - the backlog then aged out against the 120 s freshness limit and was
     * dropped. Eight seconds and two attempts still rides out a slow probe and a device that is
     * genuinely gone is now known to be gone in under twenty.
     *
     * <p>Both are ceilings, not settings: an installation that configured a shorter timeout keeps
     * it, because patience is the only thing being taken away here.
     */
    static ModemConfig recoveryModemConfig(AgentConfig config) {
        AgentConfig.ModemsConfig modems = config.modems();
        return ModemConfig.builder(config.gatewayId())
                .baudRate(modems.baudRate())
                .responseTimeoutMs(Math.min(modems.responseTimeoutMs(), RECOVERY_RESPONSE_TIMEOUT_MS))
                .commandWaitMs(modems.commandWaitMs())
                .pollWaitMs(modems.pollWaitMs())
                .probeAttempts(Math.min(modems.probeAttempts(), RECOVERY_PROBE_ATTEMPTS))
                .maxConsecutiveErrors(modems.maxConsecutiveErrors())
                .simPin(modems.simPin())
                .pduSubmission(PduSubmission.valueOf(modems.pduSubmission()))
                .build();
    }

    static ModemConfig modemConfig(AgentConfig config) {
        AgentConfig.ModemsConfig modems = config.modems();
        return ModemConfig.builder(config.gatewayId())
                .baudRate(modems.baudRate())
                .responseTimeoutMs(modems.responseTimeoutMs())
                .commandWaitMs(modems.commandWaitMs())
                .pollWaitMs(modems.pollWaitMs())
                .probeAttempts(modems.probeAttempts())
                .maxConsecutiveErrors(modems.maxConsecutiveErrors())
                .simPin(modems.simPin())
                .pduSubmission(PduSubmission.valueOf(modems.pduSubmission()))
                .build();
    }

    private static String version(String implementationVersion) {
        return implementationVersion == null || implementationVersion.isBlank()
                ? "0.1.0-SNAPSHOT" : implementationVersion;
    }

    /**
     * Releases the dead port and claims the modem again from the configured paths.
     *
     * <p>Called only after a send failed, i.e. rarely, and it performs the full init (SIM, PIN,
     * registration, PDU mode) because a modem that reappeared on the bus is not ready to send.
     */
    static Modem reopenModem(AgentConfig config, SerialPortFactory factory, AtomicReference<Modem> claimed)
            throws ModemException {
        Modem stale = claimed.get();
        if (stale != null) {
            closeQuietly(stale);
        }
        Modem fresh = claimModem(config, factory, recoveryModemConfig(config));
        claimed.set(fresh);
        if (LOG.isInfoEnabled()) {
            LOG.info("modem reopened: port={} state={}", fresh.portName(), fresh.state());
        }
        return fresh;
    }

    private static void closeQuietly(Modem modem) {
        try {
            modem.close();
        } catch (IOException | RuntimeException e) {
            LOG.debug("closing the scan modem failed: {}", e.getMessage());
        }
    }

    /** Configured operators of this gateway, for logging and diagnostics. */
    static List<String> operators(AgentConfig config) {
        return config.operators();
    }
}
