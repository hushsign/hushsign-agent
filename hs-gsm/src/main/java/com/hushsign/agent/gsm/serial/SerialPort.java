package com.hushsign.agent.gsm.serial;

import java.io.Closeable;
import java.io.IOException;

/**
 * Minimal serial-port abstraction (P1-S2). Implementations: jSerialComm
 * (production) and test fakes. This is the injectable I/O seam of hs-gsm.
 */
public interface SerialPort extends Closeable {

    /** System port name, e.g. {@code COM3} or {@code /dev/serial/by-id/usb-...}. */
    String name();

    boolean isOpen();

    /** Opens the port; blocks up to {@code timeoutMs}. */
    void open(int timeoutMs) throws IOException;

    @Override
    void close();

    void writeBytes(byte[] bytes) throws IOException;

    void writeByte(int b) throws IOException;

    /** Bytes available for reading, or 0. */
    int available() throws IOException;

    /** One byte, or -1 when nothing is available. */
    int readByte() throws IOException;

    /** Serial parameters: baud, data bits (8), stop bits (1), parity (none). */
    void configure(int baudRate, int dataBits, int stopBits, int parity);

    /** Blocking read timeout in ms (0 = no timeout). */
    void setReadTimeout(int timeoutMs);
}
