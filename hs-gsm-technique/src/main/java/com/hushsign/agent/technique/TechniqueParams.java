package com.hushsign.agent.technique;

/**
 * Parameter shapes for the {@link Technique} generators.
 *
 * <p>P1-S14 catalog decision: every catalogued technique is parameterless —
 * WAP Push SL/SI and MWI activation were dropped because they are visible on
 * modern phones; only the silent forms (plus the quiet MWI deactivation) stay.
 */
public sealed interface TechniqueParams {

    /** No parameters — the only shape the silent catalog accepts. */
    record None() implements TechniqueParams {
        public static final None INSTANCE = new None();
    }

    /**
     * The single visible-text technique (P7-S9). Deliberately kept out of the silent catalogue
     * and shipped disabled on both sides.
     *
     * <p>Not a general SMS gateway: the text is a fixed constant
     * ({@link Technique#SMS_DELIVERY_TEXT}) supplied by the technique, never by the caller.
     * That is the whole reason this shape exists as a marker with no payload field — an
     * emission command cannot carry text, so it cannot be turned into an arbitrary send.
     */
    record VisibleText() implements TechniqueParams {
        public static final VisibleText INSTANCE = new VisibleText();
    }
}
