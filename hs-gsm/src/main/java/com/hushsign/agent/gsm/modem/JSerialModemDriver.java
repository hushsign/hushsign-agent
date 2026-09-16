package com.hushsign.agent.gsm.modem;

import com.hushsign.agent.gsm.serial.SerialPort;
import org.smslib.gateway.modem.driver.AbstractModemDriver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeoutException;

/**
 * Binds the vendored AT layer to an injectable {@link SerialPort} (P1-S2).
 *
 * <p>Serial semantics carried over from the field-proven fork (P0-S7):
 * 8N1, configurable open timeout, blocking reads bounded by the configured
 * response timeout. The background {@code PollReader} drains bytes from the
 * port into the AT layer's line buffer.
 */
public final class JSerialModemDriver extends AbstractModemDriver {

    private final SerialPort port;
    private final ModemConfig config;
    private PollReader pollReader;

    public JSerialModemDriver(SerialPort port, ModemConfig config, ModemContext modem) {
        super(modem);
        this.port = port;
        this.config = config;
    }

    @Override
    public void openPort() throws IOException, TimeoutException {
        port.open(config.openTimeoutMs());
        port.configure(config.baudRate(), 8, SerialPortConfig.ONE_STOP_BIT, SerialPortConfig.NO_PARITY);
        port.setReadTimeout(config.responseTimeoutMs());

        setIn(new SerialPortInputStream(port));
        setOut(new SerialPortOutputStream(port));

        pollReader = new PollReader();
        pollReader.setName("hs-gsm-poll-" + port.name());
        pollReader.start();
    }

    @Override
    public void closePort() throws IOException {
        if (pollReader != null) {
            pollReader.cancel();
        }
        port.close();
    }

    @Override
    public String getPortInfo() {
        return port.name();
    }

    /**
     * Overrides the vendored timing defaults so the AT layer uses this modem's
     * configuration (probes want a short timeout; steady state wants the
     * upstream defaults). {@code char_wait_unit=0} batches each AT command into
     * a single port write instead of the legacy per-character delay — modern
     * modules accept full-line bursts, and jSerialComm writes arrays atomically.
     */
    @Override
    public String getModemSettings(String key) throws IOException {
        return switch (key) {
            case "timeout" -> Integer.toString(config.responseTimeoutMs());
            case "command_wait_unit" -> Integer.toString(config.commandWaitMs());
            case "wait_unit" -> Integer.toString(config.pollWaitMs());
            case "char_wait_unit" -> "0";
            default -> super.getModemSettings(key);
        };
    }

    /** Parity/stop-bit constants for {@link SerialPort#configure(int, int, int, int)}. */
    public static final class SerialPortConfig {
        public static final int ONE_STOP_BIT = 1;
        public static final int NO_PARITY = 0;

        private SerialPortConfig() {
        }
    }

    private static final class SerialPortInputStream extends InputStream {
        private final SerialPort port;

        SerialPortInputStream(SerialPort port) {
            this.port = port;
        }

        @Override
        public int read() throws IOException {
            return port.readByte();
        }

        @Override
        public int available() throws IOException {
            return port.available();
        }
    }

    private static final class SerialPortOutputStream extends OutputStream {
        private final SerialPort port;

        SerialPortOutputStream(SerialPort port) {
            this.port = port;
        }

        @Override
        public void write(int b) throws IOException {
            port.writeByte(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (off == 0 && len == b.length) {
                port.writeBytes(b);
            } else {
                byte[] slice = new byte[len];
                System.arraycopy(b, off, slice, 0, len);
                port.writeBytes(slice);
            }
        }
    }
}
