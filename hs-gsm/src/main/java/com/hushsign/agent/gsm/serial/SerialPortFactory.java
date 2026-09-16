package com.hushsign.agent.gsm.serial;

import java.util.List;

/** Factory seam for enumerating and opening serial ports (P1-S2). */
public interface SerialPortFactory {

    /** Names of all serial ports currently known to the OS. */
    List<String> listPorts();

    /** Opens the named port (not yet connected — call {@link SerialPort#open(int)}). */
    SerialPort open(String portName);
}
