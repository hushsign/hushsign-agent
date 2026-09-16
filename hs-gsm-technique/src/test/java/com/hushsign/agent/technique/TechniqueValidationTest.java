package com.hushsign.agent.technique;

import org.ajwcc.pduUtils.gsm3040.Pdu;
import org.ajwcc.pduUtils.gsm3040.PduParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * P1-S4 input validation and destination-encoding checks.
 */
class TechniqueValidationTest {

    private static final PduParser PARSER = new PduParser();

    @Test
    void parameterShapeMustMatchTheTechnique() {
        assertThrows(IllegalArgumentException.class,
                () -> Technique.WAP_PUSH_SL.generate("40756270541", TechniqueParams.None.INSTANCE),
                "WAP pushes require a scheme");
        assertThrows(IllegalArgumentException.class,
                () -> Technique.WAP_PUSH_SI.generate("40756270541",
                        new TechniqueParams.MwiToggle(true, 1)),
                "SI must reject MWI params");
        assertThrows(IllegalArgumentException.class,
                () -> Technique.MWI_TOGGLE.generate("40756270541", TechniqueParams.None.INSTANCE),
                "MWI requires toggle params");
        assertThrows(IllegalArgumentException.class,
                () -> Technique.SILENT_TP0.generate("40756270541",
                        new TechniqueParams.WapPush(TechniqueParams.WapScheme.HTTP)),
                "SILENT_TP0 takes no params");
        assertThrows(IllegalArgumentException.class,
                () -> new TechniqueParams.MwiToggle(true, 256),
                "MWI count must fit one octet");
        assertThrows(IllegalArgumentException.class,
                () -> new TechniqueParams.WapPush(null),
                "scheme must not be null");
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
