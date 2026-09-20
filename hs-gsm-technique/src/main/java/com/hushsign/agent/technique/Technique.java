package com.hushsign.agent.technique;

import java.util.HexFormat;

/**
 * The technique catalog: six silent-SMS ping variants, each a fixed template
 * whose user data was captured from HushSMS and cross-checked with PduSpy.
 *
 * <p>{@link #generate} assembles a complete SMS-SUBMIT PDU hex string for a
 * destination MSISDN: zero-length SMSC octet ({@code 00}), first octet,
 * message reference {@code 00}, BCD destination address (type {@code 91}),
 * TP-PID, TP-DCS, TP-UDL and the technique's user data. Byte-for-byte output
 * against the capture corpus is enforced in {@code TechniqueGoldenTest}.
 *
 * <p>Per HushSign invariant 8, the only content a ping carries is the
 * technique's fixed template — destinations are the only variable input.
 */
public enum Technique {

    /** Silent SMS Type 0: TP-PID 0x40, empty user data. */
    SILENT_TP0("silent_tp0", "Silent SMS Type 0 (TP-PID 0x40, empty user data)", 0x21, 0x40, 0x00, ""),
    /** 0-byte WAP push: port-addressed UDH to the WAP port, one payload octet. */
    WAP_PUSH_EMPTY("wap_push_empty", "0-byte WAP push", 0x61, 0x00, 0x04,
            "0605040B840000" + "00"),
    /** Empty MMS notification (WAP push to the MMS port, no attachments). */
    MMS_NOTIFY_EMPTY("mms_notify_empty", "Empty MMS notification", 0x61, 0x00, 0x04,
            "0605040B840000" + "7C0603BEAF848C969831323334008D93BE3132333400"),
    /**
     * MWI deactivation only (P1-S14 decision): clears the voicemail icon with no
     * user-visible effect — activation was removed from the catalog because it
     * produces a visible notification on modern phones.
     */
    MWI_DEACTIVATE("mwi_deactivate", "MWI deactivation (voicemail icon clear)", 0x61, 0x00, 0x00,
            "040102000000");

    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private final String code;
    private final String description;
    private final int firstOctet;
    private final int pid;
    private final int dcs;
    private final String udTemplate;

    Technique(String code, String description, int firstOctet, int pid, int dcs, String udTemplate) {
        this.code = code;
        this.description = description;
        this.firstOctet = firstOctet;
        this.pid = pid;
        this.dcs = dcs;
        this.udTemplate = udTemplate;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    /**
     * Generates the SMS-SUBMIT PDU hex string for this technique.
     *
     * @param destinationDigits E.164 digits of the target (no '+', 1-15 digits)
     * @param params the parameter shape this technique expects (see {@link TechniqueParams})
     * @return uppercase PDU hex string, ready for the modem's {@code AT+CMGS}
     */
    public String generate(String destinationDigits, TechniqueParams params) {
        validateDestination(destinationDigits);
        String udHex = userData(params);
        int udl = udHex.length() / 2;
        String da = encodeBcd(destinationDigits);
        return "00"
                + HEX.toHexDigits((byte) firstOctet)
                + "00"
                + HEX.toHexDigits((byte) destinationDigits.length())
                + "91"
                + da
                + HEX.toHexDigits((byte) pid)
                + HEX.toHexDigits((byte) dcs)
                + HEX.toHexDigits((byte) udl)
                + udHex;
    }

    private String userData(TechniqueParams params) {
        requireParams(params);
        return udTemplate;
    }

    private void requireParams(TechniqueParams params) {
        if (params != null && !(params instanceof TechniqueParams.None)) {
            throw new IllegalArgumentException(code + " takes no parameters, got " + params.getClass().getSimpleName());
        }
    }

    private static void validateDestination(String digits) {
        if (digits == null || digits.isEmpty() || digits.length() > 15) {
            throw new IllegalArgumentException("destination must be 1-15 E.164 digits, got '" + digits + "'");
        }
        for (int i = 0; i < digits.length(); i++) {
            if (!Character.isDigit(digits.charAt(i))) {
                throw new IllegalArgumentException("destination must be digits only, got '" + digits + "'");
            }
        }
    }

    /** Swaps nibble pairs into semi-octet BCD, padding with F on odd length. */
    private static String encodeBcd(String digits) {
        String padded = digits.length() % 2 == 1 ? digits + "F" : digits;
        StringBuilder bcd = new StringBuilder(padded.length());
        for (int i = 0; i < padded.length(); i += 2) {
            bcd.append(padded.charAt(i + 1)).append(padded.charAt(i));
        }
        return bcd.toString();
    }
}
