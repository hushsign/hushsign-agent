package com.hushsign.agent.gsm.modem;

import com.hushsign.agent.gsm.serial.SerialPort;
import com.hushsign.agent.gsm.serial.SerialPortFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Scans the host's serial ports for a GSM modem (P1-S2): every port is opened
 * and probed with {@code AT}/{@code AT+CGSN} (via {@link Modem#open}); the first
 * port that answers like a modem wins, noise ports are closed and skipped.
 */
public final class ModemScanner {

    private static final Logger log = LoggerFactory.getLogger(ModemScanner.class);

    private final SerialPortFactory factory;
    private final ModemConfig config;

    public ModemScanner(SerialPortFactory factory, ModemConfig config) {
        this.factory = factory;
        this.config = config;
    }

    /** Returns the first modem found, or empty when no port answers. */
    public Optional<Modem> scanFirst() {
        for (String portName : factory.listPorts()) {
            SerialPort port;
            try {
                port = factory.open(portName);
            } catch (RuntimeException e) {
                log.warn("cannot open port {}, skipping", portName, e);
                continue;
            }
            // NOSONAR java:S2583 - the production factory never returns null, but
            // SerialPortFactory is a seam and the test fake maps port names to ports, so a name
            // from listPorts() can be missing. Dropping the branch would turn that into an NPE.
            if (port == null) {
                log.warn("port factory returned null for {}, skipping", portName);
                continue;
            }
            try {
                // Probe-only: scanning must stay cheap; the full modem init
                // (SIM/PIN, registration, PDU mode) runs when the runtime
                // claims the modem.
                Modem modem = Modem.open(port, config.probeOnly());
                log.info("modem found on {}", portName);
                return Optional.of(modem); // ownership of the port transfers to the modem
            } catch (ModemException e) {
                log.info("no modem on {}: {}", portName, e.getMessage());
                closeQuietly(portName, port);
            } catch (RuntimeException e) {
                log.warn("skipping port {} after unexpected error", portName, e);
                closeQuietly(portName, port);
            }
        }
        return Optional.empty();
    }

    private void closeQuietly(String portName, SerialPort port) {
        try {
            port.close();
        } catch (RuntimeException closeError) {
            log.debug("error closing {} after failed probe", portName, closeError);
        }
    }
}
