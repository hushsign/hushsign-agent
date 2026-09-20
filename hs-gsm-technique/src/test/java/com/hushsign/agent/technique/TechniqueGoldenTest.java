package com.hushsign.agent.technique;

import org.ajwcc.pduUtils.gsm3040.Pdu;
import org.ajwcc.pduUtils.gsm3040.PduParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-S4 golden tests: every technique, generated with the exact parameters of
 * the HushSMS capture session, must reproduce the captured PDU byte-for-byte
 * (normalized with the zero-length SMSC octet, see hs-gsm GoldenCorpusTest).
 * Each generated PDU is also parsed back by the vendored codec and checked
 * field-by-field.
 */
class TechniqueGoldenTest {

    private static final PduParser PARSER = new PduParser();

    record GoldenCase(String name, Technique technique, TechniqueParams params, String expectedHex) {
    }

    static List<GoldenCase> cases() {
        return List.of(
                new GoldenCase("silent-tp0", Technique.SILENT_TP0, TechniqueParams.None.INSTANCE,
                        "0021000B910457260745F1400000"),
                new GoldenCase("wap-push-empty", Technique.WAP_PUSH_EMPTY, TechniqueParams.None.INSTANCE,
                        "0061000B910457260745F10004080605040B84000000"),
                new GoldenCase("mms-notify-empty", Technique.MMS_NOTIFY_EMPTY, TechniqueParams.None.INSTANCE,
                        "0061000B910457260745F100041D0605040B8400007C0603BEAF848C969831323334008D93BE3132333400"),
                new GoldenCase("mwi-deactivate", Technique.MWI_DEACTIVATE, TechniqueParams.None.INSTANCE,
                        "0061000B910457260745F1000006040102000000"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void generatedPduMatchesCaptureByteForByte(GoldenCase c) {
        String hex = c.technique().generate("40756270541", c.params());
        assertEquals(c.expectedHex(), hex, c.name() + " must match the HushSMS capture");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void generatedPduParsesToExpectedFields(GoldenCase c) {
        Pdu pdu = PARSER.parsePdu(c.technique().generate("40756270541", c.params()));

        assertEquals("40756270541", pdu.getAddress(), c.name() + " destination");
        assertEquals(c.technique() == Technique.SILENT_TP0 ? 0x40 : 0x00,
                pdu.getProtocolIdentifier(), c.name() + " TP-PID");
        boolean sevenBit = c.technique() == Technique.SILENT_TP0 || c.technique() == Technique.MWI_DEACTIVATE;
        assertEquals(sevenBit ? 0x00 : 0x04, pdu.getDataCodingScheme(), c.name() + " TP-DCS");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void wapAndMmsFamiliesCarryTheWapPortUdh(GoldenCase c) {
        if (!(c.technique().name().startsWith("WAP") || c.technique().name().startsWith("MMS"))) {
            return;
        }
        Pdu pdu = PARSER.parsePdu(c.technique().generate("40756270541", c.params()));
        assertTrue(pdu.hasTpUdhi(), c.name() + " must set TP-UDHI");
        assertTrue(pdu.isPortedMessage(), c.name() + " must carry a 16-bit port UDH");
        assertEquals(0x0B84, pdu.getDestPort(), c.name() + " destination port");
        assertEquals(0, pdu.getSrcPort(), c.name() + " source port");
    }
}
