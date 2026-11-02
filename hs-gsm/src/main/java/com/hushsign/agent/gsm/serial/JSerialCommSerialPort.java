package com.hushsign.agent.gsm.serial;

import java.io.IOException;

/**
 * jSerialComm-backed {@link SerialPort}. Field-proven semantics carried over
 * from the internal fork (P0-S7): 8N1, 10 s open timeout, blocking reads with
 * a bounded read timeout.
 */
public final class JSerialCommSerialPort implements SerialPort {

    /** How long one write may spend draining into the driver before it is called short. */
    private static final long WRITE_TIMEOUT_MS = 5_000;

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
        // Unconditionally, and deliberately not guarded by isOpen().
        //
        // After the dongle re-enumerates, jSerialComm reports the handle it gave us as not open -
        // the node it pointed at is gone - while this process still holds the file descriptor.
        // Skipping the close then leaks the descriptor, and because the same path name comes back
        // (ttyUSB1 gone, ttyUSB1 again), opening it a second time in the same JVM fails: the agent
        // answered every reopen with "cannot open ttyUSB1" until the service was restarted, which
        // made automatic modem recovery impossible in exactly the case it exists for. closePort()
        // on a handle that is already closed is a no-op, so there is nothing to protect against.
        delegate.closePort();
    }

    @Override
    public void writeBytes(byte[] bytes) throws IOException {
        int offset = 0;
        // jSerialComm reports how much of the buffer it accepted, and a driver with a full buffer
        // accepts part of it. Throwing on that turned "the modem is briefly busy" into "the modem
        // is dead" - which is exactly how a working device was declared unreachable during the
        // 2026-11-02 field test. A bounded retry is what a blocking write is for.
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(WRITE_TIMEOUT_MS);
        while (offset < bytes.length) {
            byte[] remaining = offset == 0 ? bytes
                    : java.util.Arrays.copyOfRange(bytes, offset, bytes.length);
            int written = delegate.writeBytes(remaining, remaining.length);
            if (written > 0) {
                offset += written;
                continue;
            }
            if (System.nanoTime() > deadline) {
                throw new IOException("short write on serial port " + name() + ": " + offset + "/"
                        + bytes.length + (delegate.isOpen() ? "" : " (the port is closed: the device re-enumerated)"));
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted writing to serial port " + name(), e);
            }
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
        } catch (RuntimeException _) {
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
