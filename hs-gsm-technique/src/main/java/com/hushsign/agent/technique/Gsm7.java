package com.hushsign.agent.technique;

import java.util.HexFormat;

/**
 * GSM 03.38 default alphabet encoding and septet packing (P7-S9).
 *
 * <p>Everything in this catalogue before {@code SMS_DELIVERY} carried an empty or binary user
 * data field, so nothing needed text encoding. A visible test message does, and getting it
 * subtly wrong produces a message that a phone renders as mojibake — which for a field test is
 * worse than not sending at all, because it looks like a fault in the network.
 *
 * <p>Only the default alphabet is supported. The extension table (curly braces, the euro sign,
 * {@code ^} and friends, which cost two septets each) and the national language tables are
 * refused rather than mangled: the catalogue's messages are ASCII, and a silent substitution
 * would be a bug that only shows on the phone.
 */
public final class Gsm7 {

    /** The 128 characters of the default alphabet, in septet order. */
    private static final String BASIC =
            "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ\u001BÆæßÉ !\"#¤%&'()*+,-./"
                    + "0123456789:;<=>?"
                    + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§"
                    + "¿abcdefghijklmnopqrstuvwxyzäöñüà";

    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private Gsm7() {
    }

    /**
     * Packs {@code text} into an upper-case hex string of packed septets.
     *
     * @throws IllegalArgumentException when the text is empty, longer than 160 septets, or
     *                                  contains a character outside the default alphabet
     */
    public static String encode(String text) {
        byte[] septets = septets(text);
        return HEX.formatHex(pack(septets));
    }

    /** How many septets {@code text} occupies; the TD-UDL field carries this, not the octets. */
    public static int septetCount(String text) {
        return septets(text).length;
    }

    private static byte[] septets(String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("an SMS with no text is a silent SMS, not this technique");
        }
        byte[] septets = new byte[text.length()];
        for (int i = 0; i < text.length(); i++) {
            int index = BASIC.indexOf(text.charAt(i));
            if (index < 0) {
                throw new IllegalArgumentException("character '" + text.charAt(i)
                        + "' is not in the GSM 03.38 default alphabet");
            }
            septets[i] = (byte) index;
        }
        if (septets.length > 160) {
            throw new IllegalArgumentException("an SMS carries at most 160 septets, got " + septets.length);
        }
        return septets;
    }

    /**
     * Packs seven-bit values into octets, least-significant bit first, exactly as 03.38 §9.2.1
     * describes: the first septet's bits go into the bottom seven bits of the first octet and
     * every following septet starts where the previous one ended, so {@code "hello"} becomes
     * {@code E8329BFD46}.
     */
    static byte[] pack(byte[] septets) {
        int octetCount = (septets.length * 7 + 7) / 8;
        byte[] out = new byte[octetCount];
        int bitOffset = 0;
        for (byte septet : septets) {
            int value = septet & 0x7F;
            int octet = bitOffset / 8;
            int shift = bitOffset % 8;
            out[octet] |= (byte) ((value << shift) & 0xFF);
            if (shift > 1) {
                // The top of the septet spills into the next octet.
                out[octet + 1] |= (byte) (value >> (8 - shift));
            }
            bitOffset += 7;
        }
        return out;
    }
}
