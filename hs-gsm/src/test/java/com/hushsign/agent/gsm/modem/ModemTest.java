package com.hushsign.agent.gsm.modem;

import org.junit.jupiter.api.Test;
import org.smslib.gateway.modem.DeviceInformation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModemTest {

    private static ModemConfig fastConfig() {
        return ModemConfig.builder("gw-test")
                .responseTimeoutMs(400)
                .commandWaitMs(10)
                .pollWaitMs(10)
                .probeAttempts(2)
                .probeRetryDelayMs(10)
                .maxConsecutiveErrors(3)
                .initializeOnOpen(false)
                .build();
    }

    private static ModemConfig fullInitConfig() {
        return ModemConfig.builder("gw-test")
                .responseTimeoutMs(400)
                .commandWaitMs(10)
                .pollWaitMs(10)
                .probeAttempts(2)
                .probeRetryDelayMs(10)
                .maxConsecutiveErrors(3)
                .initializeOnOpen(true)
                .build();
    }

    private static Modem openedModem(TestSerialPort port, String... openScript) throws Exception {
        for (String line : openScript) {
            port.scriptResponse(line);
        }
        return Modem.open(port, fastConfig());
    }

    @Test
    void openProbesSerialAndPopulatesDeviceInformation() throws Exception {
        TestSerialPort port = new TestSerialPort("COM3");
        port.scriptResponse("OK\r\n");
        port.scriptResponse("356789123456789\r\nOK\r\n");

        Modem modem = Modem.open(port, fastConfig());

        assertEquals("COM3", modem.portName());
        assertEquals(ModemState.READY, modem.state());
        assertEquals("356789123456789", modem.getDeviceInformation().getSerialNo());
        assertEquals(115200, port.baudRate());
        assertEquals(400, port.readTimeoutMs());
        assertEquals("AT\rAT+CGSN\r", port.sentText());
    }

    @Test
    void noisePortIsRejectedAfterProbeAttempts() {
        TestSerialPort port = new TestSerialPort("COM1");

        assertThrows(ModemException.class, () -> Modem.open(port, fastConfig()));
        assertFalse(port.isOpen(), "noise port must be closed by the caller");
    }

    @Test
    void sendPduReturnsMessageIndexAndResetsState() throws Exception {
        TestSerialPort port = new TestSerialPort("COM3");
        Modem modem = openedModem(port, "OK\r\n", "356789123456789\r\nOK\r\n");
        port.scriptResponse("> ");
        port.scriptResponse("");
        port.scriptResponse("+CMGS: 4\r\nOK\r\n");

        int index = modem.sendPdu("0011000B916407211112F20000FF04D4E2940A");

        assertEquals(4, index);
        assertEquals(ModemState.READY, modem.state());
    }

    @Test
    void rejectedPduDegradesTheModem() throws Exception {
        TestSerialPort port = new TestSerialPort("COM3");
        Modem modem = openedModem(port, "OK\r\n", "356789123456789\r\nOK\r\n");
        port.scriptResponse("> ");
        port.scriptResponse("");
        port.scriptResponse("+CMS ERROR: 500\r\n");

        assertThrows(ModemException.class, () -> modem.sendPdu("0011"));
        assertEquals(ModemState.DEGRADED, modem.state());
    }

    @Test
    void consecutiveFailuresQuarantineTheModem() throws Exception {
        TestSerialPort port = new TestSerialPort("COM3");
        Modem modem = openedModem(port, "OK\r\n", "356789123456789\r\nOK\r\n");

        for (int i = 0; i < 3; i++) {
            port.scriptResponse("> ");
            port.scriptResponse("");
            port.scriptResponse("+CMS ERROR: 500\r\n");
            assertThrows(ModemException.class, () -> modem.sendPdu("0011"));
        }

        assertEquals(ModemState.ERROR, modem.state());
        assertThrows(ModemException.class, () -> modem.sendPdu("0011"),
                "quarantined modem must refuse sends");
    }

    @Test
    void sendTimesOutWhenThePromptNeverComes() throws Exception {
        TestSerialPort port = new TestSerialPort("COM3");
        Modem modem = openedModem(port, "OK\r\n", "356789123456789\r\nOK\r\n");
        // the modem accepts AT+CMGS but never answers with the "> " prompt
        port.scriptResponse("");

        assertThrows(ModemException.class, () -> modem.sendPdu("0011"),
                "a missing prompt must time out, not hang the send thread");
        assertEquals(ModemState.DEGRADED, modem.state());
    }

    @Test
    void sendPduRejectsMalformedHex() throws Exception {
        TestSerialPort port = new TestSerialPort("COM3");
        Modem modem = openedModem(port, "OK\r\n", "356789123456789\r\nOK\r\n");

        assertThrows(IllegalArgumentException.class, () -> modem.sendPdu("xyz"));
        assertThrows(IllegalArgumentException.class, () -> modem.sendPdu("001"));
        assertThrows(IllegalArgumentException.class, () -> modem.sendPdu(null));
        assertEquals(ModemState.READY, modem.state(), "rejected inputs must not touch modem state");
    }

    @Test
    void sendSurfacesTheModemRefusalVerbatim() throws Exception {
        // P1-S14 field spike: a Huawei K3765 with a dead SIM answers AT+CMGS
        // with "+CME ERROR: 13" instead of the ">" prompt — the refusal must
        // reach the caller, not degrade into a generic send failure.
        TestSerialPort port = new TestSerialPort("COM3");
        Modem modem = openedModem(port, "OK\r\n", "356789123456789\r\nOK\r\n");
        port.scriptResponse("+CME ERROR: 13\r\n");

        ModemException failure = assertThrows(ModemException.class, () -> modem.sendPdu("0011"));
        assertTrue(failure.getMessage().contains("+CME ERROR: 13"),
                "the modem's refusal must be surfaced: " + failure.getMessage());
        assertEquals(ModemState.DEGRADED, modem.state());
    }

    @Test
    void splitResponseFramesDoNotCorruptTheDialogue() throws Exception {
        // P1-S14 field spike on Windows: the vendor serial driver delivers
        // '\r', '\n' and the next line in separate chunks. The legacy
        // delete(line.length() + 2) ate the first byte of the next line
        // ("OK" became "K") and the probe failed. Chunked frames must parse.
        TestSerialPort port = new TestSerialPort("COM3");
        Modem modem = openedModem(port, "OK\r\n", "356789123456789\r\nOK\r\n");
        port.scriptResponse("> ");
        port.scriptResponse("");
        port.dripBytes(15);
        port.scriptResponse("+CMGS: 4\r\nOK\r\n");

        int index = modem.sendPdu("0011000B916407211112F20000FF04D4E2940A");

        assertEquals(4, index, "the +CMGS index must survive split frames");
        assertEquals(ModemState.READY, modem.state());
    }

    @Test
    void tpduHexSubmissionDropsTheSmscOctet() throws Exception {
        // P1-S14: the Huawei K3765 firmware accepts AT+CMGS only with the
        // TPDU (zero-length SMSC octet dropped) and the shortened length —
        // the full form is rejected with +CMS ERROR: 304.
        TestSerialPort port = new TestSerialPort("COM3");
        port.scriptResponse("OK\r\n");
        port.scriptResponse("356789123456789\r\nOK\r\n");

        Modem modem = Modem.open(port, ModemConfig.builder("gw-test")
                .responseTimeoutMs(400)
                .commandWaitMs(10)
                .pollWaitMs(10)
                .probeAttempts(2)
                .probeRetryDelayMs(10)
                .maxConsecutiveErrors(3)
                .initializeOnOpen(false)
                .pduSubmission(PduSubmission.TPDU_HEX)
                .build());
        port.scriptResponse("> ");
        port.scriptResponse("");
        port.scriptResponse("+CMGS: 4,\r\nOK\r\n"); // trailing comma like the K3765

        int index = modem.sendPdu("0011");

        assertEquals(4, index, "the index must survive the dangling comma");
        assertTrue(port.sentText().startsWith("AT\rAT+CGSN\rAT+CMGS=1\r11"),
                "TPDU-only wire format: " + port.sentText());
    }

    @Test
    void openRunsTheFullInitSequenceAndSwitchesToPduMode() throws Exception {
        TestSerialPort port = new TestSerialPort("COM3");
        port.scriptResponse("OK\r\n");                    // probe: AT
        port.scriptResponse("356789123456789\r\nOK\r\n"); // probe: CGSN
        // initializeModem writes AT x4 and ATE0 with skipResponse; each write
        // still consumes one scripted slot in the fake.
        port.scriptResponse("");
        port.scriptResponse("");
        port.scriptResponse("");
        port.scriptResponse("");
        port.scriptResponse("");                          // ATE0 (echo off)
        port.scriptResponse("SIMCOM\r\nOK\r\n");          // CGMI
        port.scriptResponse("SIM7600\r\nOK\r\n");         // CGMM
        port.scriptResponse("");                          // ATE0 again
        port.scriptResponse("+CPIN: READY\r\nOK\r\n");    // SIM status
        port.scriptResponse("OK\r\n");                    // CLIP=1
        port.scriptResponse("OK\r\n");                    // CREG=1
        port.scriptResponse("OK\r\n");                    // CREG?
        port.scriptResponse("OK\r\n");                    // CMEE=0
        port.scriptResponse("OK\r\n");                    // CMGF=0 (PDU mode)
        port.scriptResponse("OK\r\n");                    // CNMI
        port.scriptResponse("OK\r\n");                    // CPMS=?
        port.scriptResponse("SIMCOM\r\nOK\r\n");          // refresh: CGMI
        port.scriptResponse("SIM7600\r\nOK\r\n");         // refresh: CGMM
        port.scriptResponse("356789123456789\r\nOK\r\n"); // refresh: CGSN
        port.scriptResponse("310150123456789\r\nOK\r\n"); // refresh: CIMI
        port.scriptResponse("SIM7600M2_R01\r\nOK\r\n");   // refresh: CGMR
        port.scriptResponse("+CSQ: 19,99\r\nOK\r\n");     // refresh: CSQ

        Modem modem = Modem.open(port, fullInitConfig());

        assertEquals(ModemState.READY, modem.state());
        assertEquals("SIMCOM", modem.getDeviceInformation().getManufacturer());
        assertEquals("SIM7600", modem.getDeviceInformation().getModel());
        assertEquals("310150123456789", modem.getDeviceInformation().getImsi());
        assertEquals(DeviceInformation.Modes.PDU, modem.getDeviceInformation().getMode());
        assertEquals(-75, modem.getDeviceInformation().getRssi());
        String tx = port.sentText();
        assertTrue(tx.contains("AT+CPIN?\r"), "SIM status check: " + tx);
        assertTrue(tx.contains("AT+CMGF=0\r"), "PDU mode switch: " + tx);
        assertTrue(port.isOpen());
    }

    @Test
    void openFailsWithTheSimStatusWhenTheSimCannotBeRead() {
        // P1-S14 field spike: the Huawei K3765 answers every SIM command with
        // "+CME ERROR: SIM failure" — the init must fail closed and say why.
        TestSerialPort port = new TestSerialPort("COM3");
        port.scriptResponse("OK\r\n");                    // probe: AT
        port.scriptResponse("356789123456789\r\nOK\r\n"); // probe: CGSN
        port.scriptResponse("");
        port.scriptResponse("");
        port.scriptResponse("");
        port.scriptResponse("");
        port.scriptResponse("");                          // ATE0 (echo off)
        port.scriptResponse("SIMCOM\r\nOK\r\n");          // CGMI
        port.scriptResponse("SIM7600\r\nOK\r\n");         // CGMM
        port.scriptResponse("");                          // ATE0 again
        for (int i = 0; i < 5; i++) {
            port.scriptResponse("+CME ERROR: SIM failure\r\n"); // CPIN? x5
        }

        ModemException failure = assertThrows(ModemException.class,
                () -> Modem.open(port, fullInitConfig()));

        assertTrue(failure.getMessage().contains("+CME ERROR: SIM failure"),
                "the SIM refusal must be named: " + failure.getMessage());
        assertFalse(port.isOpen(), "a failed init must close the port");
    }
}
