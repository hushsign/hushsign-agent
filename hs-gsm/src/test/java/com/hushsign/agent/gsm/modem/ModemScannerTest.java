package com.hushsign.agent.gsm.modem;

import com.hushsign.agent.gsm.serial.SerialPort;
import com.hushsign.agent.gsm.serial.SerialPortFactory;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModemScannerTest {

    private static class FakeFactory implements SerialPortFactory {
        private final Map<String, TestSerialPort> ports = new LinkedHashMap<>();

        FakeFactory with(String name) {
            ports.put(name, new TestSerialPort(name));
            return this;
        }

        TestSerialPort port(String name) {
            return ports.get(name);
        }

        @Override
        public List<String> listPorts() {
            return List.copyOf(ports.keySet());
        }

        @Override
        public SerialPort open(String portName) {
            return ports.get(portName);
        }
    }

    private static ModemConfig scanConfig() {
        return ModemConfig.builder("gw-test")
                .responseTimeoutMs(150)
                .commandWaitMs(10)
                .pollWaitMs(10)
                .probeAttempts(2)
                .probeRetryDelayMs(10)
                .build();
    }

    @Test
    void scanFindsTheModemAndSkipsNoisePorts() {
        FakeFactory factory = new FakeFactory().with("COM1").with("COM3");
        factory.port("COM3").scriptResponse("OK\r\n");
        factory.port("COM3").scriptResponse("356789123456789\r\nOK\r\n");

        ModemScanner scanner = new ModemScanner(factory, scanConfig());
        Optional<Modem> found = scanner.scanFirst();

        assertTrue(found.isPresent());
        assertEquals("COM3", found.get().portName());
        assertEquals("356789123456789", found.get().getDeviceInformation().getSerialNo());
        assertTrue(!factory.port("COM1").isOpen(), "noise port must be closed");
        assertTrue(factory.port("COM3").isOpen(), "modem port stays open");
    }

    @Test
    void scanRetriesBeforeGivingUpOnAPort() {
        FakeFactory factory = new FakeFactory().with("COM5");
        // first AT attempt times out, second succeeds
        factory.port("COM5").scriptResponse("OK\r\n");
        factory.port("COM5").scriptResponse("123456789012345\r\nOK\r\n");

        ModemScanner scanner = new ModemScanner(factory, scanConfig());
        Optional<Modem> found = scanner.scanFirst();

        assertTrue(found.isPresent(), "second probe attempt must succeed");
        assertEquals("123456789012345", found.get().getDeviceInformation().getSerialNo());
    }

    @Test
    void scanReturnsEmptyWhenNothingAnswers() {
        FakeFactory factory = new FakeFactory().with("COM1").with("COM2");

        ModemScanner scanner = new ModemScanner(factory, scanConfig());
        Optional<Modem> found = scanner.scanFirst();

        assertTrue(found.isEmpty());
    }
}
