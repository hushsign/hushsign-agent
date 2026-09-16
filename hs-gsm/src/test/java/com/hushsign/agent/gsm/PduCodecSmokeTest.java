package com.hushsign.agent.gsm;

import org.ajwcc.pduUtils.gsm3040.Pdu;
import org.ajwcc.pduUtils.gsm3040.PduFactory;
import org.ajwcc.pduUtils.gsm3040.PduGenerator;
import org.ajwcc.pduUtils.gsm3040.PduParser;
import org.ajwcc.pduUtils.gsm3040.SmsSubmitPdu;
import org.ajwcc.pduUtils.wappush.WapSiPdu;
import org.junit.jupiter.api.Test;
import org.smslib.message.MsIsdn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-S1 smoke tests: the vendored GSM 03.40 codec generates and parses PDUs.
 * The full golden corpus from public capture vectors lands in P1-S3.
 */
class PduCodecSmokeTest {

    private static final PduGenerator GENERATOR = new PduGenerator();
    private static final PduParser PARSER = new PduParser();

    @Test
    void submitRoundTripsAddressTextAndPid() {
        SmsSubmitPdu pdu = PduFactory.newSmsSubmitPdu();
        pdu.setAddress(new MsIsdn("+40722111222"));
        pdu.setDecodedText("Test");

        String hex = GENERATOR.generatePduString(pdu);
        assertTrue(hex.matches("^[0-9A-F]+$"), "generated PDU must be uppercase hex, got: " + hex);
        assertTrue(hex.startsWith("00"), "SMSC field must be empty (00), got: " + hex);

        Pdu parsed = PARSER.parsePdu(hex);
        assertEquals("40722111222", parsed.getAddress());
        assertEquals("Test", parsed.getDecodedText());
        assertEquals(0, parsed.getProtocolIdentifier());
    }

    @Test
    void silentPidFlowsThroughGenerationAndParsing() {
        SmsSubmitPdu pdu = PduFactory.newSmsSubmitPdu();
        pdu.setAddress(new MsIsdn("+40722111222"));
        pdu.setDecodedText(""); // silent SMS: empty user data
        pdu.setProtocolIdentifier(0x40); // silent SMS type 0 (TP-PID 0x40)

        Pdu parsed = PARSER.parsePdu(GENERATOR.generatePduString(pdu));
        assertEquals(0x40, parsed.getProtocolIdentifier(), "silent SMS PID must survive a round-trip");
    }

    @Test
    void wapSiPduGeneratesHex() {
        WapSiPdu pdu = PduFactory.newWapSiPdu();
        pdu.setAddress(new MsIsdn("+40722111222"));
        pdu.setSiId("hs-1");
        pdu.setUrl("http://hushsign.dev");
        pdu.setIndicationText("ping");

        String hex = GENERATOR.generatePduString(pdu);
        assertTrue(hex.matches("^[0-9A-F]+$") && !hex.isEmpty());
    }
}
