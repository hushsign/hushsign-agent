package com.hushsign.agent.technique;

import java.util.HexFormat;
import java.util.List;

/**
 * The technique catalog: the four shipped silent-SMS ping variants, each a fixed
 * template whose user data was captured from HushSMS and cross-checked with PduSpy.
 *
 * <p>{@link #generate} assembles a complete SMS-SUBMIT PDU hex string for a
 * destination MSISDN: zero-length SMSC octet ({@code 00}), first octet,
 * message reference {@code 00}, BCD destination address (type {@code 91}),
 * TP-PID, TP-DCS, TP-UDL and the technique's user data. Byte-for-byte output
 * against the capture corpus is enforced in {@code TechniqueGoldenTest}.
 *
 * <p>Per HushSign invariant 8, the only content a ping carries is the
 * technique's fixed template — destinations are the only variable input.
 *
 * <p>Wire codes are the enum names ({@link #fromWireCode}); {@link #code()} is the
 * stable lowercase catalog id the platform persists. Not every code in the
 * append-only protocol enum has a generator: {@link #RESERVED_WIRE_CODES} are
 * reserved (withdrawn after the P1-S14 field sweep) and are refused explicitly
 * instead of masquerading as an unknown technique.
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

    /**
     * Codes the protocol enum carries but this build does not generate: they stay
     * in the wire contract (append-only) and are refused with an explicit reason.
     */
    public static final List<String> RESERVED_WIRE_CODES =
            List.of("WAP_PUSH_SL", "WAP_PUSH_SI", "MWI_TOGGLE");

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

    /**
     * The wire code this build advertises for the technique (the enum name, e.g.
     * {@code SILENT_TP0}) — the same value the platform puts in
     * {@code EmissionCommand.technique}.
     */
    public String wireCode() {
        return name();
    }

    /** Wire codes of every technique this build can actually send, in catalog order. */
    public static List<String> supportedWireCodes() {
        return List.of(values()).stream().map(Technique::wireCode).toList();
    }

    /**
     * Resolves a wire code carried by a command.
     *
     * @throws IllegalArgumentException when the code is unknown to the protocol, or
     *         known but reserved in this build ({@link #RESERVED_WIRE_CODES}) — the
     *         caller turns this into a {@code FAILED} transmission with the exact reason
     */
    public static Technique fromWireCode(String wireCode) {
        String code = wireCode == null ? "" : wireCode.trim();
        if (RESERVED_WIRE_CODES.contains(code)) {
            throw new IllegalArgumentException(
                    "technique " + code + " is not implemented by this build (reserved in the catalog)");
        }
        for (Technique technique : values()) {
            if (technique.name().equals(code)) {
                return technique;
            }
        }
        throw new IllegalArgumentException("unknown technique " + code);
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
