package com.hushsign.agent;

import com.hushsign.agent.config.AgentConfig;
import com.hushsign.agent.config.AgentConfigLoader;
import com.hushsign.agent.gsm.modem.Modem;
import com.hushsign.agent.gsm.modem.ModemException;
import com.hushsign.agent.gsm.modem.ModemState;
import com.hushsign.agent.gsm.serial.SerialPort;
import com.hushsign.agent.gsm.serial.SerialPortFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-S2 / P1-S14 tests: claiming a modem from the configured port list. A real
 * dongle re-enumerates, so the port a file was written for can simply not exist
 * when the agent starts - that is the case these tests pin down.
 */
class AgentMainTest {

    @TempDir
    Path tempDir;

    /** Opens nothing: every name is unknown to the machine, like a stale ttyUSB index. */
    private static final class MissingPorts implements SerialPortFactory {
        private final List<String> present;
        private final List<String> attempts = new ArrayList<>();

        MissingPorts(List<String> present) {
            this.present = present;
        }

        List<String> attempts() {
            return attempts;
        }

        @Override
        public List<String> listPorts() {
            return present;
        }

        @Override
        public SerialPort open(String portName) {
            attempts.add(portName);
            // What JSerialCommPortFactory does for a port that is not there.
            throw new IllegalArgumentException("unknown serial port: " + portName);
        }
    }


    /**
     * A port that answers like a healthy modem: the values the init parses, and {@code OK} to
     * everything else. Scripting the whole smslib sequence byte for byte would pin the test to
     * the vendor driver's init order instead of to the behaviour under test - a reopen has to
     * release the old descriptor and publish the new modem.
     */
    private static final class HealthyModemPort implements SerialPort {
        private final String name;
        private final Deque<Byte> pending = new ArrayDeque<>();
        private final List<String> written = new ArrayList<>();
        private boolean open;
        private boolean closed;

        HealthyModemPort(String name) {
            this.name = name;
        }

        boolean closed() {
            return closed;
        }

        /** The commands this port received, without the trailing carriage return. */
        List<String> written() {
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

    /** Hands out one scripted port per open() call, in order. */
    private static final class ScriptedFactory implements SerialPortFactory {
        private final List<HealthyModemPort> ports = new ArrayList<>();
        private int handedOut;

        void add(String name) {
            ports.add(new HealthyModemPort(name));
        }

        HealthyModemPort last() {
            return ports.get(ports.size() - 1);
        }

        @Override
        public List<String> listPorts() {
            return ports.stream().map(HealthyModemPort::name).toList();
        }

        @Override
        public SerialPort open(String portName) {
            HealthyModemPort port = ports.get(Math.min(handedOut++, ports.size() - 1));
            port.open(1000);
            return port;
        }
    }

    private AgentConfig configWithPorts(List<String> ports) throws IOException {
        Path file = tempDir.resolve("agent.yaml");
        String quoted = String.join(", ", ports.stream().map(p -> "\"" + p + "\"").toList());
        Files.writeString(file, """
                gatewayId: 5e0f7a10-0000-4000-8000-00000000c0de
                operators: ["226-10"]
                modems:
                  autoScan: false
                  ports: [%s]
                  responseTimeoutMs: 300
                  commandWaitMs: 10
                  pollWaitMs: 5
                  probeAttempts: 2
                """.formatted(quoted));
        return AgentConfigLoader.load(file, Map.of());
    }

    @Test
    void reopeningReleasesTheDeadPortAndPublishesTheFreshModem() throws Exception {
        // The case that took a gateway off the air: the dongle re-enumerated, the agent kept the
        // descriptor it had claimed at startup, and every send failed on a port that no longer
        // existed. A reopen has to let go of that descriptor before it claims the next one, and
        // the reference the heartbeat reads must point at the modem that is actually working.
        AgentConfig config = configWithPorts(List.of("/dev/serial/by-id/dongle"));
        ScriptedFactory factory = new ScriptedFactory();
        factory.add("/dev/serial/by-id/dongle");
        Modem dead = AgentMain.claimModem(config, factory);
        HealthyModemPort deadPort = factory.last();

        factory.add("/dev/serial/by-id/dongle");
        AtomicReference<Modem> claimed = new AtomicReference<>(dead);
        Modem fresh = AgentMain.reopenModem(config, factory, claimed);

        assertNotSame(dead, fresh, "a reopen must produce a new modem, not the one that died");
        assertTrue(deadPort.closed(), "the stale descriptor has to be released before reopening");
        assertSame(fresh, claimed.get(), "the runtime reads this reference for status and shutdown");
        assertEquals(ModemState.READY, fresh.state());
        assertFalse(factory.last().closed(), "the modem the agent is now using stays open");
    }

    @Test
    void aPortThatNoLongerExistsDoesNotStopTheNextOneFromBeingTried() throws Exception {
        AgentConfig config = configWithPorts(List.of("/dev/ttyUSB0", "/dev/ttyUSB1"));
        MissingPorts factory = new MissingPorts(List.of("/dev/ttyUSB1"));

        assertThrows(ModemException.class, () -> AgentMain.claimModem(config, factory));

        assertEquals(List.of("/dev/ttyUSB0", "/dev/ttyUSB1"), factory.attempts(),
                "the first missing port used to abort the whole claim and hide the second one");
    }

    @Test
    void theFailureSaysWhichPortsTheMachineDoesHave() throws Exception {
        AgentConfig config = configWithPorts(List.of("/dev/ttyUSB0"));
        MissingPorts factory = new MissingPorts(List.of("/dev/ttyUSB1", "/dev/ttyUSB2"));

        ModemException error = assertThrows(ModemException.class,
                () -> AgentMain.claimModem(config, factory));

        assertTrue(error.getMessage().contains("/dev/ttyUSB0"), error.getMessage());
        assertTrue(error.getMessage().contains("[/dev/ttyUSB1, /dev/ttyUSB2]"), error.getMessage());
    }

    @Test
    void anIndexedDeviceNameGetsTheByidHint() {
        String message = AgentMain.noConfiguredPortAnswered(
                List.of("/dev/ttyUSB0"), List.of("/dev/ttyUSB1"));

        assertTrue(message.contains("/dev/serial/by-id"), message);
    }

    @Test
    void aPinnedByidPathIsNotNagged() {
        String message = AgentMain.noConfiguredPortAnswered(
                List.of("/dev/serial/by-id/usb-HUAWEI_Technology_HUAWEI_Mobile-if00-port0"),
                List.of());

        assertFalse(message.contains("pin the"), message);
    }
}
