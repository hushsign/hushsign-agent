package com.hushsign.agent.technique;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * P7-S9: the 7-bit packer, against the vectors published with GSM 03.38.
 *
 * <p>The {@code "hello"} case is the one from the specification's own worked example, which is
 * why it is here: an off-by-one in the bit offset produces plausible-looking hex that a phone
 * renders as garbage.
 */
class Gsm7Test {

    /** The default alphabet, repeated so the decoder in this test does not read production state. */
    private static final String BASIC =
            "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ\u001BÆæßÉ !\"#¤%&'()*+,-./"
                    + "0123456789:;<=>?"
                    + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§"
                    + "¿abcdefghijklmnopqrstuvwxyzäöñüà";

    @Test
    void thePublishedHelloVectorPacksCorrectly() {
        // The spec's worked example. The final octet is the one that differs between
        // implementations: seven bits of 'o' end in 0b110 and the remaining five bits of the
        // last octet are fill, which 03.38 says to zero. Sources quoting "E8329BFD46" have a
        // set fill bit; both decode to "hello", and a phone ignores the difference.
        assertEquals("E8329BFD06", Gsm7.encode("hello"));
        assertEquals(5, Gsm7.septetCount("hello"));
    }

    @Test
    void packingSurvivesARoundTripThroughTheBits() {
        for (String text : new String[]{"A", "AB", "hello", "HushSign test SMS", "1234567890", "a b c"}) {
            String hex = Gsm7.encode(text);

            assertEquals(text, decode(hex, Gsm7.septetCount(text)),
                    "packing then unpacking must return the original for " + text);
        }
    }

    @Test
    void aSingleCharacterIsItsOwnSeptet() {
        assertEquals("41", Gsm7.encode("A"));
        assertEquals(1, Gsm7.septetCount("A"));
    }

    @Test
    void twoCharactersSpillIntoTheSecondOctet() {
        // 'A' = 0x41, 'B' = 0x42. The second septet's low bit lands in the spare high bit of
        // the first octet, and the rest shifts down one.
        assertEquals("4121", Gsm7.encode("AB"));
    }

    @Test
    void digitsAndTheDocumentedPunctuationAreInTheAlphabet() {
        assertEquals(8, Gsm7.septetCount("12345678"));
        assertEquals(1, Gsm7.septetCount("@"));
        assertEquals(1, Gsm7.septetCount("£"));
    }

    @Test
    void aCharacterOutsideTheDefaultAlphabetIsRefusedRatherThanMangled() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> Gsm7.encode("temperatură"));

        assertTrue(refused.getMessage().contains("not in the GSM 03.38 default alphabet"));
    }

    @Test
    void anEmojiIsRefusedToo() {
        assertThrows(IllegalArgumentException.class, () -> Gsm7.encode("ok \uD83D\uDE00"));
    }

    @Test
    void emptyTextIsNotThisTechnique() {
        assertThrows(IllegalArgumentException.class, () -> Gsm7.encode(""));
        assertThrows(IllegalArgumentException.class, () -> Gsm7.encode(null));
    }

    @Test
    void moreThanOneHundredAndSixtySeptetsIsRefused() {
        String tooLong = "A".repeat(161);
        assertThrows(IllegalArgumentException.class, () -> Gsm7.encode(tooLong));
        assertEquals(160, Gsm7.septetCount("A".repeat(160)));
    }

    /**
     * The inverse of the packer, written here rather than in the production class: nothing in
     * the agent needs to decode, and a second implementation in the test is what makes the
     * round-trip a check rather than a tautology.
     */
    private static String decode(String hex, int septets) {
        byte[] octets = HexFormat.of().parseHex(hex);
        StringBuilder out = new StringBuilder(septets);
        int bitOffset = 0;
        for (int i = 0; i < septets; i++) {
            int octet = bitOffset / 8;
            int shift = bitOffset % 8;
            int value = (octets[octet] & 0xFF) >>> shift;
            if (shift > 1 && octet + 1 < octets.length) {
                value |= (octets[octet + 1] & 0xFF) << (8 - shift);
            }
            out.append((char) BASIC.charAt(value & 0x7F));
            bitOffset += 7;
        }
        return out.toString();
    }
}
