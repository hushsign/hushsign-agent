package com.hushsign.agent.gsm.modem;

import com.hushsign.agent.gsm.serial.SerialPort;
import org.smslib.core.Capabilities;
import org.smslib.gateway.modem.DeviceInformation;
import org.smslib.gateway.modem.ModemResponse;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * A single GSM modem (P1-S2): the fresh modem abstraction on top of the
 * vendored AT layer.
 *
 * <ul>
 *   <li>open + probe: {@code AT} sync with retries, then {@code AT+CGSN};</li>
 *   <li>one send at a time (synchronized); state {@code READY → BUSY → READY},
 *       consecutive failures degrade to {@code DEGRADED} and finally
 *       {@code ERROR} (quarantine);</li>
 *   <li>implements {@link ModemContext}, the seam the vendored AT layer reads.</li>
 * </ul>
 */
public final class Modem implements ModemContext, AutoCloseable {

    private final JSerialModemDriver driver;
    private final SerialPort port;
    private final ModemConfig config;
    private final DeviceInformation deviceInformation = new DeviceInformation();
    private final Capabilities capabilities = new Capabilities();

    private volatile ModemState state = ModemState.READY;
    private int consecutiveErrors;

    private Modem(SerialPort port, ModemConfig config) {
        this.port = port;
        this.config = config;
        this.driver = new JSerialModemDriver(port, config, this);
    }

    /**
     * Opens the port and probes it as a modem ({@code AT} sync with retries,
     * then {@code AT+CGSN}). Throws {@link ModemException} if the port does not
     * answer like a modem.
     */
    public static Modem open(SerialPort port, ModemConfig config) throws ModemException {
        Modem modem = new Modem(port, config);
        modem.probe();
        return modem;
    }

    private void probe() throws ModemException {
        try {
            driver.openPort();
        } catch (IOException | TimeoutException e) {
            closeQuietly();
            throw new ModemException("cannot open " + port.name(), e);
        }

        ModemResponse response = syncAtWithRetries();
        if (response == null || !response.isResponseOk()) {
            closeQuietly();
            throw new ModemException("port " + port.name() + " does not answer AT after "
                    + config.probeAttempts() + " attempts");
        }

        try {
            ModemResponse serial = driver.write("AT+CGSN\r");
            if (!serial.isResponseOk()) {
                closeQuietly();
                throw new ModemException("port " + port.name() + " refused AT+CGSN");
            }
            deviceInformation.setSerialNo(serial.getResponseData().trim());
        } catch (IOException | TimeoutException | NumberFormatException | InterruptedException e) {
            closeQuietly();
            throw new ModemException("AT+CGSN probe failed on " + port.name(), e);
        }
        state = ModemState.READY;
    }

    private void closeQuietly() {
        try {
            driver.closePort();
        } catch (IOException ignored) {
            // best effort
        }
    }

    private ModemResponse syncAtWithRetries() throws ModemException {
        ModemResponse last = null;
        for (int attempt = 1; attempt <= config.probeAttempts(); attempt++) {
            try {
                last = driver.write("AT\r");
                if (last.isResponseOk()) {
                    return last;
                }
                driver.clearResponses();
            } catch (TimeoutException e) {
                clearResponsesQuietly();
            } catch (IOException | NumberFormatException e) {
                throw new ModemException("AT sync failed on " + port.name(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ModemException("AT sync interrupted on " + port.name(), e);
            }
            sleepQuietly(config.probeRetryDelayMs());
        }
        return last;
    }

    private void clearResponsesQuietly() {
        try {
            driver.clearResponses();
        } catch (IOException | NumberFormatException ignored) {
            // best effort
        }
    }

    private static void sleepQuietly(long ms) throws ModemException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModemException("interrupted while waiting for modem", e);
        }
    }

    /**
     * Sends one PDU (hex string). Serialized per modem: one send at a time.
     *
     * @return the modem message index from {@code +CMGS}, or -1 when refused
     */
    public synchronized int sendPdu(String pduHex) throws ModemException {
        if (pduHex == null || pduHex.length() % 2 != 0 || !pduHex.matches("[0-9A-Fa-f]+")) {
            throw new IllegalArgumentException("pduHex must be even-length hex, got '" + pduHex + "'");
        }
        if (state == ModemState.ERROR) {
            throw new ModemException("modem " + port.name() + " is quarantined (ERROR)");
        }
        if (state == ModemState.BUSY) {
            throw new ModemException("modem " + port.name() + " is busy");
        }
        state = ModemState.BUSY;
        try {
            int index = driver.atSendPDUMessage(pduHex.length() / 2, pduHex);
            if (index < 0) {
                throw new ModemException("modem " + port.name() + " rejected the PDU");
            }
            consecutiveErrors = 0;
            state = ModemState.READY;
            return index;
        } catch (ModemException e) {
            recordFailure();
            throw e;
        } catch (Exception e) {
            recordFailure();
            throw new ModemException("send failed on " + port.name(), e);
        }
    }

    private void recordFailure() {
        consecutiveErrors++;
        state = consecutiveErrors >= config.maxConsecutiveErrors()
                ? ModemState.ERROR
                : ModemState.DEGRADED;
    }

    /** Populates manufacturer, model, serial, IMSI, SW version and RSSI via the AT layer. */
    public synchronized void refreshDeviceInformation() throws ModemException {
        try {
            driver.refreshDeviceInformation();
            state = ModemState.READY;
            consecutiveErrors = 0;
        } catch (Exception e) {
            recordFailure();
            throw new ModemException("device information refresh failed on " + port.name(), e);
        }
    }

    public ModemState state() {
        return state;
    }

    public String portName() {
        return port.name();
    }

    @Override
    public DeviceInformation getDeviceInformation() {
        return deviceInformation;
    }

    @Override
    public String getGatewayId() {
        return config.gatewayId();
    }

    @Override
    public String getSimPin() {
        return config.simPin();
    }

    @Override
    public String getSimPin2() {
        return config.simPin2();
    }

    @Override
    public void setCapabilities(Capabilities caps) {
        capabilities.clear(Capabilities.Caps.CanSendMessage);
        capabilities.set(Capabilities.Caps.CanSendMessage);
    }

    public Capabilities capabilities() {
        return capabilities;
    }

    @Override
    public synchronized void close() throws IOException {
        driver.closePort();
    }
}
