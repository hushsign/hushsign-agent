package com.hushsign.agent.gsm.serial;

import java.util.Arrays;
import java.util.List;

/** Production {@link SerialPortFactory} backed by jSerialComm. */
public final class JSerialCommPortFactory implements SerialPortFactory {

    @Override
    public List<String> listPorts() {
        return Arrays.stream(com.fazecast.jSerialComm.SerialPort.getCommPorts())
                .map(com.fazecast.jSerialComm.SerialPort::getSystemPortName)
                .toList();
    }

    @Override
    public SerialPort open(String portName) {
        com.fazecast.jSerialComm.SerialPort port =
                com.fazecast.jSerialComm.SerialPort.getCommPort(portName);
        if (port == null) {
            throw new IllegalArgumentException("unknown serial port: " + portName);
        }
        return new JSerialCommSerialPort(port);
    }
}
