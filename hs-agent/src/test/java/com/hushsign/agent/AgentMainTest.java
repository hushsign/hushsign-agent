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
    void aRecoveryAttemptIsImpatientComparedWithAStartupClaim() throws Exception {
        // The send path is the only consumer: a recovery inside it blocks every queued command, so
        // its timeouts are the difference between a stall of seconds and one of ninety. Measured
        // on 2026-11-02: one unplug stalled the agent for 87 s against the startup profile.
        AgentConfig config = configWithPorts(List.of("/dev/serial/by-id/dongle"));

        int configured = AgentMain.modemConfig(config).responseTimeoutMs();
        int recovery = AgentMain.recoveryModemConfig(config).responseTimeoutMs();
        assertTrue(recovery <= configured, "a recovery never waits longer than the installation asked");
        assertTrue(recovery <= 8_000, "and never longer than eight seconds, whatever the config says");
        assertTrue(AgentMain.recoveryModemConfig(config).probeAttempts()
                <= Math.min(2, AgentMain.modemConfig(config).probeAttempts()), "at most two attempts");
    }
    @Test
    void reopeningReleasesTheDeadPortAndPublishesTheFreshModem() throws Exception {
        // The case that took a gateway off the air: the dongle re-enumerated, the agent kept the
        // descriptor it had claimed at startup, and every send failed on a port that no longer
        // existed. A reopen has to let go of that descriptor before it claims the next one, and
        // the reference the heartbeat reads must point at the modem that is actually working.
        AgentConfig config = configWithPorts(List.of("/dev/serial/by-id/dongle"));
        HealthyModemSupport.Factory factory = new HealthyModemSupport.Factory();
        factory.add("/dev/serial/by-id/dongle");
        Modem dead = AgentMain.claimModem(config, factory);
        HealthyModemSupport.Port deadPort = factory.last();

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
    void theFailureSaysWhatTheConfiguredPathResolvedTo() throws Exception {
        // "serial ports present: [ttyUSB1, ttyUSB2, ttyUSB3]" next to "none of the configured ports
        // answered" reads as a contradiction: the device is right there. It is not one - the
        // configured path is a by-id link, and the link is what failed. The message has to say so.
        String message = AgentMain.noConfiguredPortAnswered(
                List.of("/dev/serial/by-id/usb-does-not-exist-port0"), List.of("ttyUSB1"));

        assertTrue(message.contains("does not exist right now"), message);
        assertTrue(message.contains("/dev/serial/by-id/usb-does-not-exist-port0"), message);
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
