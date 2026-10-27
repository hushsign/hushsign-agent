package com.hushsign.agent.runtime.sensitive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * P7-S5: the scrubber catches identities that were never put into a {@link Sensitive} — the
 * policy reason strings, exception messages and third-party log lines that end up in a support
 * bundle.
 */
class SensitiveRedactorTest {

    @Test
    void anImsiInFreeTextIsMasked() {
        String scrubbed = SensitiveRedactor.scrub("modem claimed imsi=310150123456789 state=READY");

        assertFalse(scrubbed.contains("310150123456789"));
        assertEquals("modem claimed imsi=31015********** state=READY", scrubbed);
    }

    @Test
    void anMsisdnInsideAPolicyReasonIsMasked() {
        String scrubbed = SensitiveRedactor.scrub(
                "policy rejected emission e-1: target +40756270541 has reached its 6-per-hour cap");

        assertFalse(scrubbed.contains("40756270541"));
        assertTrue(scrubbed.contains("+4075*******"));
        assertTrue(scrubbed.endsWith("has reached its 6-per-hour cap"), "the reason must stay readable");
    }

    @Test
    void anIccidIsTreatedAsAnIdentityToo() {
        String scrubbed = SensitiveRedactor.scrub("iccid=8944010000000000000");

        assertEquals("iccid=89440**************", scrubbed, "a SIM serial identifies the SIM");
    }

    @Test
    void shortAndVeryLongDigitRunsAreLeftAlone() {
        assertEquals("port 8080 count 42", SensitiveRedactor.scrub("port 8080 count 42"));
        String oversizedId = "123456789012345678901";
        assertEquals(oversizedId, SensitiveRedactor.scrub(oversizedId),
                "21 digits is past any subscriber identifier, so it is not guessed at");
    }

    @Test
    void anAlreadyMaskedValueIsNotMaskedAgain() {
        String once = SensitiveRedactor.scrub("imsi=31015**********");

        assertEquals("imsi=31015**********", once);
        assertEquals(once, SensitiveRedactor.scrub(once), "scrubbing must be idempotent");
    }

    @Test
    void severalIdentitiesOnOneLineAreAllMasked() {
        String scrubbed = SensitiveRedactor.scrub("from 310150123456789 to +40756270541");

        assertEquals("from 31015********** to +4075*******", scrubbed);
    }

    @Test
    void textWithoutIdentitiesIsReturnedUnchanged() {
        String line = "agent started gateway=gw-1 operators=[226-01]";

        assertEquals(line, SensitiveRedactor.scrub(line));
        assertFalse(SensitiveRedactor.containsIdentity(line));
        assertTrue(SensitiveRedactor.containsIdentity("imsi 310150123456789"));
    }

    @Test
    void nullIsTolerated() {
        assertNull(SensitiveRedactor.scrub(null));
        assertFalse(SensitiveRedactor.containsIdentity(null));
    }
}
