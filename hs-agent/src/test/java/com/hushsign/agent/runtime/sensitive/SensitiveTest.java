package com.hushsign.agent.runtime.sensitive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * P7-S5: the masked form is what a caller gets without asking, and asking is the only way to
 * the raw value.
 */
class SensitiveTest {

    @Test
    void toStringIsTheMaskedForm() {
        Sensitive imsi = Sensitive.of("310150123456789");

        assertEquals("31015**********", imsi.toString(), "logging the object must be safe by default");
        assertEquals("310150123456789", imsi.reveal(), "the raw value is still available on request");
    }

    @Test
    void anMsisdnKeepsOnlyTheCountryAndPrefix() {
        assertEquals("+4075*******", Sensitive.of("+40756270541").masked());
    }

    @Test
    void shortValuesAreFullyMasked() {
        assertEquals("*****", Sensitive.of("12345").masked(), "nothing survives when there is not enough to spare");
        assertEquals("****", Sensitive.of("1234").masked());
    }

    @Test
    void nullAndBlankBecomeEmptyRatherThanFailing() {
        assertEquals("", Sensitive.of(null).masked());
        assertFalse(Sensitive.of(null).isPresent());
        assertEquals("", Sensitive.of("  ").masked());
        assertEquals("", Sensitive.mask(null));
    }

    @Test
    void equalityIsOnTheRawValue() {
        assertEquals(Sensitive.of("310150123456789"), Sensitive.of("310150123456789"));
        assertFalse(Sensitive.of("310150123456789").equals(Sensitive.of("310150123456788")));
    }

    @Test
    void theMaskIsStableAcrossRepeatedCalls() {
        Sensitive imsi = Sensitive.of("226010123456789");

        assertEquals(imsi.masked(), imsi.masked(), "a support bundle must be correlatable with the device log");
        assertTrue(imsi.masked().startsWith("22601"), "the network is worth keeping, the subscriber is not");
    }
}
