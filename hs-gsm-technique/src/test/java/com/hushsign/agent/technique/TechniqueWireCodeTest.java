package com.hushsign.agent.technique;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-S7/P1-S13: the wire enum is append-only, but not every code in it has a
 * generator in this build. Resolution must distinguish "unknown" from
 * "reserved but not implemented", so the platform gets an actionable cause.
 */
class TechniqueWireCodeTest {

    @Test
    void everyCatalogTechniqueResolvesByItsWireCode() {
        for (Technique technique : Technique.values()) {
            assertEquals(technique, Technique.fromWireCode(technique.wireCode()));
        }
    }

    @Test
    void supportedWireCodesAreTheEnumNames() {
        assertEquals(List.of("SILENT_TP0", "WAP_PUSH_EMPTY", "MMS_NOTIFY_EMPTY", "MWI_DEACTIVATE"),
                Technique.supportedWireCodes());
    }

    @Test
    void reservedCodesAreRefusedWithTheirOwnReason() {
        for (String reserved : Technique.RESERVED_WIRE_CODES) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> Technique.fromWireCode(reserved));
            assertTrue(e.getMessage().contains("reserved in the catalog"), e.getMessage());
        }
    }

    @Test
    void unknownAndMissingCodesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> Technique.fromWireCode("LASER_PING"));
        assertThrows(IllegalArgumentException.class, () -> Technique.fromWireCode(null));
        assertThrows(IllegalArgumentException.class, () -> Technique.fromWireCode("silent_tp0"),
                "the lowercase catalog id is not a wire code");
    }

    @Test
    void surroundingWhitespaceIsTolerated() {
        assertEquals(Technique.SILENT_TP0, Technique.fromWireCode(" SILENT_TP0 "));
    }
}
