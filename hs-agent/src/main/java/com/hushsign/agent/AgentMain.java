package com.hushsign.agent;

import com.hushsign.agent.config.AgentConfig;
import com.hushsign.agent.config.AgentConfigLoader;
import com.hushsign.agent.gsm.modem.Modem;
import com.hushsign.agent.gsm.modem.ModemConfig;
import com.hushsign.agent.gsm.modem.ModemException;
import com.hushsign.agent.gsm.modem.ModemScanner;
import com.hushsign.agent.gsm.modem.PduSubmission;
import com.hushsign.agent.gsm.serial.JSerialCommPortFactory;
import com.hushsign.agent.gsm.serial.SerialPortFactory;
import com.hushsign.agent.runtime.AgentRuntime;
import com.hushsign.agent.runtime.sensitive.LogDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

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
        if (!LogDirectory.harden(LogDirectory.configured())) {
            LOG.info("log directory is not owner-only; agent.log carries raw SIM identities, so keep it on the device");
        }
        Path configFile = configPath(args);
        AgentConfig config = AgentConfigLoader.load(configFile);
        LOG.info("starting agent gateway={} operators={} pduSubmission={}",
                config.gatewayId(), config.operators(), config.modems().pduSubmission());

        Modem modem = claimModem(config, new JSerialCommPortFactory());
        LOG.info("modem claimed: port={} state={}", modem.portName(), modem.state());

        AgentRuntime runtime = AgentRuntime.start(config, configFile, modem,
                version(AgentMain.class.getPackage().getImplementationVersion()));
        try {
            runtime.awaitTermination();
        } finally {
            runtime.close();
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
    static Modem claimModem(AgentConfig config, SerialPortFactory factory) throws ModemException, IOException {
        ModemConfig modemConfig = modemConfig(config);
        if (!config.modems().autoScan()) {
            for (String port : config.modems().ports()) {
                try {
                    return Modem.open(factory.open(port), modemConfig);
                } catch (ModemException e) {
                    LOG.warn("no usable modem on {}: {}", port, e.getMessage());
                }
            }
            throw new ModemException("none of the configured ports answered: " + config.modems().ports());
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
