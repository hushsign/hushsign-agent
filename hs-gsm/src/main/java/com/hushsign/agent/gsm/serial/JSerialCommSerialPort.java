package com.hushsign.agent.gsm.serial;

import java.io.IOException;

/**
 * jSerialComm-backed {@link SerialPort}. Field-proven semantics carried over
 * from the internal fork (P0-S7): 8N1, 10 s open timeout, blocking reads with
 * a bounded read timeout.
 */
public final class JSerialCommSerialPort implements SerialPort {

    private final com.fazecast.jSerialComm.SerialPort delegate;

    JSerialCommSerialPort(com.fazecast.jSerialComm.SerialPort delegate) {
        this.delegate = delegate;
    }

    @Override
    public String name() {
        return delegate.getSystemPortName();
    }

    @Override
    public boolean isOpen() {
        return delegate.isOpen();
    }

    @Override
    public void open(int timeoutMs) throws IOException {
        if (!delegate.openPort(timeoutMs)) {
            throw new IOException("failed to open serial port " + name());
        }
    }

    @Override
    public void close() {
        if (delegate.isOpen()) {
            delegate.closePort();
        }
    }

    @Override
    public void writeBytes(byte[] bytes) throws IOException {
        int written = delegate.writeBytes(bytes, bytes.length);
        if (written != bytes.length) {
            // a short (or failed) write would silently corrupt a PDU
            throw new IOException("short write on serial port " + name()
                    + ": " + written + "/" + bytes.length);
        }
    }

    @Override
    public void writeByte(int b) throws IOException {
        writeBytes(new byte[]{(byte) b});
    }

    @Override
    public int available() {
        return delegate.bytesAvailable();
    }

    @Override
    public int readByte() throws IOException {
        byte[] one = new byte[1];
        int read;
        try {
            read = delegate.readBytes(one, 1);
        } catch (RuntimeException e) {
            // jSerialComm surfaces read timeouts as an unchecked exception.
            return -1;
        }
        return read > 0 ? one[0] & 0xFF : -1;
    }

    @Override
    public void configure(int baudRate, int dataBits, int stopBits, int parity) {
        delegate.setComPortParameters(baudRate, dataBits, stopBits, parity);
    }

    @Override
    public void setReadTimeout(int timeoutMs) {
        // Blocking read with the given timeout; writes also block-bounded.
        delegate.setComPortTimeouts(
                com.fazecast.jSerialComm.SerialPort.TIMEOUT_READ_BLOCKING
                        | com.fazecast.jSerialComm.SerialPort.TIMEOUT_WRITE_BLOCKING,
                timeoutMs, timeoutMs);
    }
}
