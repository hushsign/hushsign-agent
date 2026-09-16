package com.hushsign.agent.gsm;

import org.ajwcc.pduUtils.gsm3040.Pdu;
import org.ajwcc.pduUtils.gsm3040.PduFactory;
import org.ajwcc.pduUtils.gsm3040.PduGenerator;
import org.ajwcc.pduUtils.gsm3040.PduParser;
import org.ajwcc.pduUtils.gsm3040.PduUtils;
import org.ajwcc.pduUtils.gsm3040.SmsSubmitPdu;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.smslib.message.MsIsdn;

import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-S3 golden corpus: PDU capture vectors from the product owner's HushSMS
 * session (decoded and cross-checked with PduSpy), covering every ping type:
 * normal SMS, Class 0 flash, silent Type 0, WAP push variants (empty, SL, SI)
 * and MWI toggles. The parser must reproduce the captured header fields and
 * user data byte-for-byte.
 *
 * <p>Captures: 01 00 0B 91 0457260745F1 ... (DA +40756270541).
 * HushSMS omits the SMSC-info field entirely when no SMSC is configured;
 * every capture starts with the SUBMIT first octet (01/21/61). The corpus
 * normalizes them by prefixing the zero-length SMSC octet ({@code 00}) that
 * the vendored parser expects.
 */
class GoldenCorpusTest {

    private static final PduParser PARSER = new PduParser();
    private static final PduGenerator GENERATOR = new PduGenerator();
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private static final String CAPTURED_HELLO_WORLD_UD = "C8329BFD065DDF723619";
    private static final String WAP_PORT_UDH = "0605040B840000";

    record GoldenVector(String id, String hex, int pid, int dcs, int udl,
                        boolean udhi, String udHex, String text) {
    }

    private static List<GoldenVector> vectors() {
        return List.of(
                // capture: 01000B910457260745F100000BC8329BFD065DDF723619
                new GoldenVector("normal-sms", "0001000B910457260745F100000BC8329BFD065DDF723619",
                        0x00, 0x00, 11, false, CAPTURED_HELLO_WORLD_UD, "Hello World"),
                // capture: 21000B910457260745F100180BC8329BFD065DDF723619
                // HushSMS quirk: DCS 0x18 flags class-0 + UCS2 per TS 23.038, but the
                // payload is packed 7-bit; the corpus asserts raw bytes, not text.
                new GoldenVector("class0-flash", "0021000B910457260745F100180BC8329BFD065DDF723619",
                        0x00, 0x18, 11, false, CAPTURED_HELLO_WORLD_UD, null),
                // capture: 21000B910457260745F1400000
                new GoldenVector("silent-type0", "0021000B910457260745F1400000",
                        0x40, 0x00, 0, false, "", ""),
                // capture: 61000B910457260745F10004080605040B84000000
                new GoldenVector("wap-push-empty", "0061000B910457260745F10004080605040B84000000",
                        0x00, 0x04, 8, true, WAP_PORT_UDH + "00", null),
                // capture: 61000B910457260745F100041D0605040B8400007C0603BEAF848C969831323334008D93BE3132333400
                new GoldenVector("mms-notify-empty",
                        "0061000B910457260745F100041D0605040B8400007C0603BEAF848C969831323334008D93BE3132333400",
                        0x00, 0x04, 0x1D, true,
                        WAP_PORT_UDH + "7C0603BEAF848C969831323334008D93BE3132333400", null),
                // capture: 61000B910457260745F10004210605040B840000DC0605B0AF82B48302066A00850803746573742E636F6D000501
                new GoldenVector("wap-sl-noprefix",
                        "0061000B910457260745F10004210605040B840000DC0605B0AF82B48302066A00850803746573742E636F6D000501",
                        0x00, 0x04, 0x21, true,
                        WAP_PORT_UDH + "DC0605B0AF82B48302066A00850803746573742E636F6D000501", null),
                // capture: ...008509... (http://)
                new GoldenVector("wap-sl-http",
                        "0061000B910457260745F10004210605040B840000DC0605B0AF82B48302066A00850903746573742E636F6D000501",
                        0x00, 0x04, 0x21, true,
                        WAP_PORT_UDH + "DC0605B0AF82B48302066A00850903746573742E636F6D000501", null),
                // capture: ...00850B... (https://)
                new GoldenVector("wap-sl-https",
                        "0061000B910457260745F10004210605040B840000DC0605B0AF82B48302066A00850B03746573742E636F6D000501",
                        0x00, 0x04, 0x21, true,
                        WAP_PORT_UDH + "DC0605B0AF82B48302066A00850B03746573742E636F6D000501", null),
                // capture: 61000B910457260745F10004340605040B840000DC060DAEAF82B483B173656E6465720002056A0045C60B03746573742E636F6D0001037375626A656374000101
                new GoldenVector("wap-si-noprefix",
                        "0061000B910457260745F10004340605040B840000DC060DAEAF82B483B173656E6465720002056A0045C60B03746573742E636F6D0001037375626A656374000101",
                        0x00, 0x04, 0x34, true,
                        WAP_PORT_UDH + "DC060DAEAF82B483B173656E6465720002056A0045C60B03746573742E636F6D0001037375626A656374000101",
                        null),
                // capture: ...0045C60C... (http://)
                new GoldenVector("wap-si-http",
                        "0061000B910457260745F10004340605040B840000DC060DAEAF82B483B173656E6465720002056A0045C60C03746573742E636F6D0001037375626A656374000101",
                        0x00, 0x04, 0x34, true,
                        WAP_PORT_UDH + "DC060DAEAF82B483B173656E6465720002056A0045C60C03746573742E636F6D0001037375626A656374000101",
                        null),
                // capture: ...0045C60E... (https://)
                new GoldenVector("wap-si-https",
                        "0061000B910457260745F10004340605040B840000DC060DAEAF82B483B173656E6465720002056A0045C60E03746573742E636F6D0001037375626A656374000101",
                        0x00, 0x04, 0x34, true,
                        WAP_PORT_UDH + "DC060DAEAF82B483B173656E6465720002056A0045C60E03746573742E636F6D0001037375626A656374000101",
                        null),
                // capture: 61000B910457260745F1000006040102000000
                new GoldenVector("mwi-deactivate", "0061000B910457260745F1000006040102000000",
                        0x00, 0x00, 6, true, "040102000000", null),
                // capture: 61000B910457260745F1000006040102800101
                new GoldenVector("mwi-count-1", "0061000B910457260745F1000006040102800101",
                        0x00, 0x00, 6, true, "040102800101", null),
                // capture: 61000B910457260745F1000006040102807B01
                new GoldenVector("mwi-count-123", "0061000B910457260745F1000006040102807B01",
                        0x00, 0x00, 6, true, "040102807B01", null));
    }

    private static GoldenVector byId(String id) {
        return vectors().stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    void capturedPduParsesToGoldenFields(GoldenVector v) {
        Pdu pdu = PARSER.parsePdu(v.hex());

        assertEquals("40756270541", pdu.getAddress(), v.id() + " destination address");
        assertEquals(v.pid(), pdu.getProtocolIdentifier(), v.id() + " TP-PID");
        assertEquals(v.dcs(), pdu.getDataCodingScheme(), v.id() + " TP-DCS");
        assertEquals(v.udl(), pdu.getUDLength(), v.id() + " TP-UDL");
        assertEquals(v.udhi(), pdu.hasTpUdhi(), v.id() + " TP-UDHI");
        assertEquals(v.udHex(), HEX.formatHex(pdu.getUDData()), v.id() + " user data bytes");
        if (v.text() != null) {
            assertEquals(v.text(), pdu.getDecodedText(), v.id() + " decoded text");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    void parsedPduMatchesTpMtiSubmit(GoldenVector v) {
        Pdu pdu = PARSER.parsePdu(v.hex());
        assertEquals(PduUtils.TP_MTI_SMS_SUBMIT, pdu.getTpMti(), v.id() + " must be SMS-SUBMIT");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wapVectors")
    void wapAndMmsPushesCarryPortAddressingToWapPort(GoldenVector v) {
        Pdu pdu = PARSER.parsePdu(v.hex());

        assertTrue(pdu.isPortedMessage(), v.id() + " must carry a 16-bit port UDH");
        assertEquals(0x0B84, pdu.getDestPort(), v.id() + " destination port must be the WAP port");
        assertEquals(0, pdu.getSrcPort(), v.id() + " source port");
        assertEquals(WAP_PORT_UDH, HEX.formatHex(pdu.getUDHData()), v.id() + " UDH bytes");
    }

    static Stream<GoldenVector> wapVectors() {
        return vectors().stream().filter(v -> v.id().startsWith("wap-") || v.id().startsWith("mms-"));
    }

    @Test
    void classZeroCarriesFlashClassWithSevenBitPackedText() {
        Pdu class0 = PARSER.parsePdu(byId("class0-flash").hex());
        Pdu normal = PARSER.parsePdu(byId("normal-sms").hex());

        assertEquals(0x18, class0.getDataCodingScheme());
        assertEquals(normal.getUDData().length, class0.getUDData().length,
                "same payload length as the normal capture");
        // HushSMS packs 7-bit data under DCS 0x18 even though TS 23.038 maps
        // 0x18 to UCS2 + class 0; decode the payload explicitly.
        assertEquals("Hello World", PduUtils.decode7bitEncoding(class0.getUDData()),
                "class-0 payload is 7-bit packed");
    }

    @Test
    void mwiVectorsEncodeSpecialSmsIndicationIe() {
        // UDH: 04 01 02 <store/active> <count> | IEI 0x01 = special SMS message indication
        Pdu deactivate = PARSER.parsePdu(byId("mwi-deactivate").hex());
        byte[] ud = deactivate.getUDData();
        assertEquals(0x01, ud[1], "IEI must be special SMS message indication");
        assertEquals(0x02, ud[2], "IE data length");
        assertEquals(0x00, ud[3] & 0xFF, "deactivation has store bit clear");
        assertEquals(0x00, ud[4] & 0xFF, "deactivation count byte");

        Pdu count1 = PARSER.parsePdu(byId("mwi-count-1").hex());
        byte[] ud1 = count1.getUDData();
        assertEquals(0x80, ud1[3] & 0xFF, "activation sets the store bit");
        assertEquals(0x01, ud1[4] & 0xFF, "count 1");
        assertEquals(0x01, ud1[5] & 0xFF, "trailing payload octet");

        Pdu count123 = PARSER.parsePdu(byId("mwi-count-123").hex());
        byte[] ud123 = count123.getUDData();
        assertEquals(0x80, ud123[3] & 0xFF, "activation sets the store bit");
        assertEquals(0x7B, ud123[4] & 0xFF, "count 123");
    }

    @Test
    void generatorReproducesCapturedNormalSubmitUserData() {
        SmsSubmitPdu pdu = PduFactory.newSmsSubmitPdu();
        pdu.setAddress(new MsIsdn("+40756270541"));
        pdu.setDataCodingScheme(0x00); // 7-bit default alphabet, as captured
        pdu.setDecodedText("Hello World");

        Pdu parsed = PARSER.parsePdu(GENERATOR.generatePduString(pdu));
        assertEquals("40756270541", parsed.getAddress());
        assertEquals("Hello World", parsed.getDecodedText());
        assertEquals(CAPTURED_HELLO_WORLD_UD, HEX.formatHex(parsed.getUDData()),
                "generated 7-bit packing must match the capture");
    }

    @Test
    void generatorPreservesClassZeroDcsRoundTrip() {
        SmsSubmitPdu pdu = PduFactory.newSmsSubmitPdu();
        pdu.setAddress(new MsIsdn("+40756270541"));
        pdu.setDataCodingScheme(0x18); // Class 0 flash
        pdu.setDecodedText("Hello World");

        // The strict generator encodes DCS 0x18 as UCS2 (unlike the HushSMS
        // capture, which 7-bit-packs); the DCS must still survive the trip.
        Pdu parsed = PARSER.parsePdu(GENERATOR.generatePduString(pdu));
        assertEquals(0x18, parsed.getDataCodingScheme());
        assertEquals("Hello World", parsed.getDecodedText());
    }

    @Test
    void generatorReproducesCapturedSilentType0() {
        SmsSubmitPdu pdu = PduFactory.newSmsSubmitPdu();
        pdu.setAddress(new MsIsdn("+40756270541"));
        pdu.setProtocolIdentifier(0x40); // silent SMS Type 0
        pdu.setDecodedText("");

        Pdu parsed = PARSER.parsePdu(GENERATOR.generatePduString(pdu));
        assertEquals(0x40, parsed.getProtocolIdentifier());
        assertEquals(0, parsed.getUDLength(), "silent SMS carries no user data");
        assertFalse(parsed.hasTpUdhi());
    }
}
