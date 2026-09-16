package com.hushsign.agent.technique;

/**
 * Parameter shapes for the {@link Technique} generators.
 *
 * <p>Each technique accepts exactly one parameter shape; passing anything else
 * is rejected with {@link IllegalArgumentException}. The shapes mirror the
 * HushSMS capture variants the golden tests verify byte-for-byte.
 */
public sealed interface TechniqueParams {

    /** No parameters. Use for SILENT_TP0, WAP_PUSH_EMPTY and MMS_NOTIFY_EMPTY. */
    record None() implements TechniqueParams {
        public static final None INSTANCE = new None();
    }

    /** URL scheme variant for WAP pushes. */
    enum WapScheme {
        /** Bare host, no prefix (HushSMS "NO PREFIX" capture). */
        NONE,
        /** http:// prefix (HushSMS "HTTP" capture). */
        HTTP,
        /** https:// prefix (HushSMS "HTTPS" capture). */
        HTTPS
    }

    /** Scheme variant for WAP_PUSH_SL and WAP_PUSH_SI. */
    record WapPush(WapScheme scheme) implements TechniqueParams {
        public WapPush {
            if (scheme == null) {
                throw new IllegalArgumentException("wap scheme must not be null");
            }
        }
    }

    /**
     * MWI activation state for MWI_TOGGLE.
     *
     * @param active store bit: true = activate with the given count, false = deactivate
     * @param count message count when activating (0-255, one octet)
     */
    record MwiToggle(boolean active, int count) implements TechniqueParams {
        public MwiToggle {
            if (count < 0 || count > 255) {
                throw new IllegalArgumentException("MWI count must fit one octet (0-255), got " + count);
            }
        }
    }
}
