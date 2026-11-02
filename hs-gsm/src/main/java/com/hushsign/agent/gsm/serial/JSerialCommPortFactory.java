package com.hushsign.agent.gsm.serial;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Production {@link SerialPortFactory} backed by jSerialComm. */
public final class JSerialCommPortFactory implements SerialPortFactory {

    /**
     * Resolves the configured name to the device the kernel has right now.
     *
     * <p>The configured path is a {@code /dev/serial/by-id/} link because that is the name that
     * survives a replug. jSerialComm matches a descriptor against the ports it enumerated, so a
     * link resolves to the {@code ttyUSB} name it had when the list was built: after the dongle
     * re-enumerated, the claim reopened a node that no longer existed and the modem was reported
     * unreachable while it was sitting on the next ttyUSB number. Resolving on every open is what
     * makes a claim after a replug land on the live port.
     */
    static String resolveCurrentDevice(String portName) {
        Path path = Path.of(portName);
        if (!Files.isSymbolicLink(path)) {
            return portName;
        }
        try {
            return path.toRealPath().toString();
        } catch (IOException e) {
            return portName;
        }
    }

    @Override
    public List<String> listPorts() {
        return Arrays.stream(com.fazecast.jSerialComm.SerialPort.getCommPorts())
                .map(com.fazecast.jSerialComm.SerialPort::getSystemPortName)
                .toList();
    }

    @Override
    public SerialPort open(String portName) {
        com.fazecast.jSerialComm.SerialPort port =
                com.fazecast.jSerialComm.SerialPort.getCommPort(resolveCurrentDevice(portName));
        if (port == null) {
            throw new IllegalArgumentException("unknown serial port: " + portName);
        }
        return new JSerialCommSerialPort(port);
    }
}
