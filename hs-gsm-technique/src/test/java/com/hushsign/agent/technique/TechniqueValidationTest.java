package com.hushsign.agent.technique;

import org.ajwcc.pduUtils.gsm3040.Pdu;
import org.ajwcc.pduUtils.gsm3040.PduParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * P1-S4 input validation and destination-encoding checks.
 */
class TechniqueValidationTest {

    private static final PduParser PARSER = new PduParser();

    @Test
    void catalogTechniquesAreParameterlessExceptTheVisibleOne() {
        for (Technique technique : Technique.values()) {
            if (technique == Technique.SMS_DELIVERY) {
                // The one exception, and it is deliberate: visible text needs a 7-bit payload.
                assertFalse(technique.generate("40756270541", TechniqueParams.VisibleText.INSTANCE).isEmpty(),
                        technique + " must generate with VisibleText params");
                continue;
            }
            String hex = technique.generate("40756270541", TechniqueParams.None.INSTANCE);
            assertFalse(hex.isEmpty(), technique + " must generate with None params");
        }
        assertEquals(5, Technique.values().length,
                "four silent techniques (Type-0, empty WAP, empty MMS, MWI deactivate) plus the "
                        + "visible test SMS that ships disabled");
    }

    @Test
    void nullParamsAreAcceptedForParameterlessTechniques() {
        String hex = Technique.SILENT_TP0.generate("40756270541", null);
        assertEquals("0021000B910457260745F1400000", hex);
    }

    @Test
    void destinationMustBeE164Digits() {
        assertThrows(IllegalArgumentException.class,
                () -> Technique.SILENT_TP0.generate("+40756270541", null),
                "plus signs are rejected");
        assertThrows(IllegalArgumentException.class,
                () -> Technique.SILENT_TP0.generate("4075627a541", null),
                "letters are rejected");
        assertThrows(IllegalArgumentException.class,
                () -> Technique.SILENT_TP0.generate("", null),
                "empty destinations are rejected");
        assertThrows(IllegalArgumentException.class,
                () -> Technique.SILENT_TP0.generate(null, null),
                "null destinations are rejected");
    }

    @Test
    void destinationEncodesAsSwappedBcdAndRoundTrips() {
        Pdu parsed = PARSER.parsePdu(Technique.SILENT_TP0.generate("40722111222", null));
        assertEquals("40722111222", parsed.getAddress());

        // odd digit count is padded with F, which the parser drops
        Pdu odd = PARSER.parsePdu(Technique.SILENT_TP0.generate("123", null));
        assertEquals("123", odd.getAddress());
    }
}
