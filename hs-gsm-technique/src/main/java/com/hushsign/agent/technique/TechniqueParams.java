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
}
