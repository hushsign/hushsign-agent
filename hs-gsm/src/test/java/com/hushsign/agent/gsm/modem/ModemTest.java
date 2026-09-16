package com.hushsign.agent.gsm.modem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModemTest {

    private static ModemConfig fastConfig() {
        return ModemConfig.builder("gw-test")
                .responseTimeoutMs(400)
                .commandWaitMs(10)
                .pollWaitMs(10)
                .probeAttempts(2)
                .probeRetryDelayMs(10)
                .maxConsecutiveErrors(3)
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
        assertEquals(false, port.isOpen(), "noise port must be closed by the caller");
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
}
