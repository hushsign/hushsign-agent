package com.hushsign.agent;

import com.hushsign.agent.config.AgentConfig;
import com.hushsign.agent.config.AgentConfigLoader;
import com.hushsign.agent.config.ConfigException;
import com.hushsign.agent.gsm.modem.Modem;
import com.hushsign.agent.gsm.modem.ModemConfig;
import com.hushsign.agent.gsm.modem.ModemException;
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
import java.nio.file.Path;
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
                });

        AgentRuntime runtime = AgentRuntime.start(config, configFile, modem, sender, claimed::get,
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
        ModemConfig modemConfig = modemConfig(config);
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
                + "; serial ports present: " + present;
        if (configured.stream().anyMatch(AgentMain::isEnumerationOrderPath)) {
            message += ". A /dev/ttyUSB<n> name is an enumeration order, not a device: pin the"
                    + " /dev/serial/by-id/ path instead and it survives the next replug";
        }
        return message;
    }

    private static boolean isEnumerationOrderPath(String port) {
        return port.matches("/dev/tty(USB|ACM)\\d+");
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
        Modem fresh = claimModem(config, factory);
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
