package com.hushsign.agent.simulator;

import com.hushsign.agent.gsm.serial.SerialPort;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mock GSM modem for the simulator (P1-S5): a scriptable {@link SerialPort}
 * that speaks just enough AT to drive the hs-gsm {@code Modem} end-to-end.
 *
 * <ul>
 *   <li>Auto-responds to {@code AT}, {@code AT+CGSN} (configured serial),
 *       {@code AT+CGMI/CGMM/CIMI/CGMR/CSQ} (canned identity + signal), and
 *       runs the {@code AT+CMGS} prompt handshake (&#8594; {@code "> "}).</li>
 *   <li>On Ctrl-Z the {@link SimScenario} decides the outcome
 *       (success index, CMS errors, or silence).</li>
 *   <li>Every byte written and every PDU payload is captured for assertions.</li>
 *   <li>Responses for any exact command can be overridden for custom scripts.</li>
 * </ul>
 *
 * <p>Thread-safe: the agent writes from its command thread while the
 * background poll reader drains {@link #available}/{@link #readByte}.
 */
public final class SimModem implements SerialPort {

    private final String name;
    private final String serialNo;
    private final Deque<Byte> rx = new ArrayDeque<>();
    private final StringBuilder tx = new StringBuilder();
    private final StringBuilder commandBuffer = new StringBuilder();
    private final StringBuilder currentPdu = new StringBuilder();
    private final List<String> receivedPdus = new ArrayList<>();
    private final Map<String, String> responseOverrides = new HashMap<>();

    private volatile SimScenario scenario;
    private boolean open;
    private boolean awaitingPdu;
    private int messageIndex;
    private int configuredBaudRate;
    private int configuredReadTimeoutMs;

    public SimModem(String name, SimScenario scenario) {
        this(name, scenario, "356789123456789");
    }

    public SimModem(String name, SimScenario scenario, String serialNo) {
        this.name = name;
        this.scenario = scenario;
        this.serialNo = serialNo;
    }

    /** Switches the scenario at runtime (success → offline transitions etc.). */
    public synchronized SimModem scenario(SimScenario scenario) {
        this.scenario = scenario;
        return this;
    }

    public synchronized SimScenario scenario() {
        return scenario;
    }

    /** Overrides the auto-response for one exact AT command (without the CR). */
    public synchronized SimModem overrideResponse(String command, String response) {
        responseOverrides.put(command, response);
        return this;
    }

    /** PDU payloads captured from completed {@code AT+CMGS} handshakes. */
    public synchronized List<String> receivedPdus() {
        return List.copyOf(receivedPdus);
    }

    /** Everything ever written to the port (AT commands, PDUs, Ctrl-Z). */
    public synchronized String sentText() {
        return tx.toString();
    }

    public synchronized int configuredBaudRate() {
        return configuredBaudRate;
    }

    public synchronized int configuredReadTimeoutMs() {
        return configuredReadTimeoutMs;
    }

    // ==================================================
    // SerialPort
    // ==================================================

    @Override
    public String name() {
        return name;
    }

    @Override
    public synchronized boolean isOpen() {
        return open;
    }

    @Override
    public synchronized void open(int timeoutMs) {
        open = true;
    }

    @Override
    public synchronized void close() {
        open = false;
    }

    @Override
    public synchronized void configure(int baudRate, int dataBits, int stopBits, int parity) {
        configuredBaudRate = baudRate;
    }

    @Override
    public synchronized void setReadTimeout(int timeoutMs) {
        configuredReadTimeoutMs = timeoutMs;
    }

    @Override
    public synchronized void writeBytes(byte[] bytes) {
        tx.append(new String(bytes, StandardCharsets.US_ASCII));
        for (byte b : bytes) {
            handleByte(b & 0xFF);
        }
    }

    @Override
    public void writeByte(int b) {
        writeBytes(new byte[]{(byte) b});
    }

    @Override
    public synchronized int available() {
        return rx.size();
    }

    @Override
    public synchronized int readByte() {
        Byte b = rx.poll();
        return b == null ? -1 : b & 0xFF;
    }

    // ==================================================
    // AT brain
    // ==================================================

    private void handleByte(int b) {
        if (b == 0x1A) {
            // Ctrl-Z finalizes the PDU handshake
            awaitingPdu = false;
            String pdu = currentPdu.toString();
            if (!pdu.isEmpty()) {
                receivedPdus.add(pdu);
            }
            currentPdu.setLength(0);
            respondToCmgs();
            return;
        }
        if (awaitingPdu) {
            char c = (char) b;
            if (Character.isDigit(c) || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f')) {
                currentPdu.append(c);
            }
            return;
        }
        if (b == '\r') {
            dispatch(commandBuffer.toString());
            commandBuffer.setLength(0);
        } else {
            commandBuffer.append((char) b);
        }
    }

    private void dispatch(String command) {
        String override = responseOverrides.get(command);
        if (override != null) {
            enqueue(override);
            return;
        }
        if (command.equals("AT")) {
            enqueue("OK\r\n");
        } else if (command.startsWith("AT+CGSN")) {
            enqueue(serialNo + "\r\nOK\r\n");
        } else if (command.startsWith("AT+CMGS=")) {
            awaitingPdu = true;
            currentPdu.setLength(0);
            enqueue("> ");
        } else if (command.startsWith("AT+CGMI")) {
            enqueue("SIMCOM\r\nOK\r\n");
        } else if (command.startsWith("AT+CGMM")) {
            enqueue("SIM7600\r\nOK\r\n");
        } else if (command.startsWith("AT+CIMI")) {
            enqueue("310150123456789\r\nOK\r\n");
        } else if (command.startsWith("AT+CGMR")) {
            enqueue("SIM7600M2_R01\r\nOK\r\n");
        } else if (command.startsWith("AT+CSQ")) {
            enqueue("+CSQ: 19,99\r\nOK\r\n");
        } else {
            enqueue("OK\r\n");
        }
    }

    private void respondToCmgs() {
        if (scenario == SimScenario.OFFLINE) {
            return; // silence until the driver times out
        }
        messageIndex++;
        switch (scenario) {
            case SUCCESS -> enqueue("+CMGS: " + messageIndex + "\r\nOK\r\n");
            case FAILURE -> enqueue("+CMS ERROR: 500\r\n");
            case POLICY_REJECT -> enqueue("+CMS ERROR: 304\r\n");
            case OFFLINE -> { /* unreachable, handled above */ }
        }
    }

    private void enqueue(String response) {
        for (byte b : response.getBytes(StandardCharsets.US_ASCII)) {
            rx.add(b);
        }
    }
}
