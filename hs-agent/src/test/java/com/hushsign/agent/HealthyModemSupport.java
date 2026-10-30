package com.hushsign.agent;

import com.hushsign.agent.gsm.modem.Modem;
import com.hushsign.agent.gsm.modem.ModemConfig;
import com.hushsign.agent.gsm.serial.SerialPort;
import com.hushsign.agent.gsm.serial.SerialPortFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A dongle that behaves: it answers the values the driver's init parses and {@code OK} to
 * everything else, so a test can get a READY {@link Modem} without a serial port.
 *
 * <p>Scripting the whole smslib sequence byte for byte would pin every test to the vendor
 * driver's init order. What these tests are about is what the agent does around a modem - claim
 * it, reopen it, report it - so the fake only has to be plausible.
 */
public final class HealthyModemSupport {

    /** The port the modem was opened on, and whether it has been closed since. */
    public static final class Port implements SerialPort {
        private final String name;
        private final Deque<Byte> pending = new ArrayDeque<>();
        private final List<String> written = new ArrayList<>();
        private boolean open;
        private boolean closed;

        public Port(String name) {
            this.name = name;
        }

        public boolean closed() {
            return closed;
        }

        public List<String> written() {
            return written;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void open(int timeoutMs) {
            open = true;
        }

        @Override
        public void close() {
            closed = true;
            open = false;
        }

        @Override
        public void writeBytes(byte[] bytes) {
            String command = new String(bytes, StandardCharsets.UTF_8).trim();
            written.add(command);
            for (byte b : replyTo(command).getBytes(StandardCharsets.UTF_8)) {
                pending.add(b);
            }
        }

        @Override
        public void writeByte(int b) {
            writeBytes(new byte[] {(byte) b});
        }

        private static String replyTo(String command) {
            if (command.startsWith("AT+CGSN")) {
                return "356789123456789\r\nOK\r\n";
            }
            if (command.startsWith("AT+CGMI")) {
                return "huawei\r\nOK\r\n";
            }
            if (command.startsWith("AT+CGMM")) {
                return "K3765\r\nOK\r\n";
            }
            if (command.startsWith("AT+CGMR")) {
                return "11.126.03.07.00\r\nOK\r\n";
            }
            if (command.startsWith("AT+CIMI")) {
                return "226100000000001\r\nOK\r\n";
            }
            if (command.startsWith("AT+CPIN?")) {
                return "+CPIN: READY\r\nOK\r\n";
            }
            if (command.startsWith("AT+CREG?")) {
                return "+CREG: 0,1\r\nOK\r\n";
            }
            if (command.startsWith("AT+CSQ")) {
                return "+CSQ: 20,99\r\nOK\r\n";
            }
            return "\r\nOK\r\n";
        }

        @Override
        public int available() {
            return pending.size();
        }

        @Override
        public int readByte() {
            Byte next = pending.poll();
            return next == null ? -1 : next & 0xFF;
        }

        @Override
        public void configure(int baudRate, int dataBits, int stopBits, int parity) {
            // the fake has no line settings to apply
        }

        @Override
        public void setReadTimeout(int timeoutMs) {
            // the fake answers immediately
        }
    }

    /** Hands out one port per open() call, reusing the last one when the caller asks twice. */
    public static final class Factory implements SerialPortFactory {
        private final List<Port> ports = new ArrayList<>();
        private int handedOut;

        public Port add(String name) {
            Port port = new Port(name);
            ports.add(port);
            return port;
        }

        public Port last() {
            return ports.get(ports.size() - 1);
        }

        @Override
        public List<String> listPorts() {
            return ports.stream().map(Port::name).toList();
        }

        @Override
        public SerialPort open(String portName) {
            Port port = ports.get(Math.min(handedOut++, ports.size() - 1));
            port.open(1_000);
            return port;
        }
    }

    private HealthyModemSupport() {
    }

    /** A modem that has just claimed {@code name} and is READY. */
    public static Modem ready(String name) throws Exception {
        return Modem.open(new Port(name), config());
    }

    /** Short timeouts: the fake answers at once, so a mismatch fails fast instead of hanging. */
    public static ModemConfig config() {
        return ModemConfig.builder("5e0f7a10-0000-4000-8000-00000000c0de")
                .baudRate(115200)
                .responseTimeoutMs(300)
                .commandWaitMs(10)
                .pollWaitMs(5)
                .probeAttempts(2)
                .probeRetryDelayMs(5)
                .maxConsecutiveErrors(3)
                .build();
    }
}
