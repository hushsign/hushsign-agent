package com.hushsign.agent.gsm.modem;

import com.hushsign.agent.gsm.serial.SerialPort;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Scriptable serial port fake (P1-S2 tests). Every {@code writeBytes} consumes
 * the next scripted response and makes it readable; ports without scripts
 * behave like silent noise.
 */
final class TestSerialPort implements SerialPort {

    private final String name;
    private final Deque<byte[]> responses = new ArrayDeque<>();
    private final Deque<Byte> rx = new ArrayDeque<>();
    private final ByteArrayOutputStream tx = new ByteArrayOutputStream();

    private boolean open;
    private int baudRate;
    private int dataBits;
    private int stopBits;
    private int parity;
    private int readTimeoutMs;

    TestSerialPort(String name) {
        this.name = name;
    }

    /** Scripts the response for the next write call; empty string = no bytes. */
    void scriptResponse(String response) {
        responses.add(response.getBytes(StandardCharsets.UTF_8));
    }

    String sentText() {
        return tx.toString(StandardCharsets.UTF_8);
    }

    int baudRate() {
        return baudRate;
    }

    int readTimeoutMs() {
        return readTimeoutMs;
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
        open = false;
    }

    @Override
    public void writeBytes(byte[] bytes) {
        tx.writeBytes(bytes);
        byte[] scripted = responses.poll();
        if (scripted != null) {
            for (byte b : scripted) {
                rx.add(b);
            }
        }
    }

    @Override
    public void writeByte(int b) throws IOException {
        writeBytes(new byte[]{(byte) b});
    }

    @Override
    public int available() {
        return rx.size();
    }

    @Override
    public int readByte() {
        Byte b = rx.poll();
        return b == null ? -1 : b & 0xFF;
    }

    @Override
    public void configure(int baudRate, int dataBits, int stopBits, int parity) {
        this.baudRate = baudRate;
        this.dataBits = dataBits;
        this.stopBits = stopBits;
        this.parity = parity;
    }

    @Override
    public void setReadTimeout(int timeoutMs) {
        this.readTimeoutMs = timeoutMs;
    }
}
