package com.hushsign.agent.runtime.sensitive;

/**
 * Whether the agent's raw log file may contain subscriber identifiers (P7-S12).
 *
 * <p>Two files are written: {@code agent-clean.log}, which is scrubbed and safe to hand over,
 * and {@code agent.log}, which is the copy that answers "which target hit a cap". The second
 * one is only worth having for as long as it carries real values - a scrubbed copy of a
 * scrubbed log is a second file that tells nobody anything.
 *
 * <p>So the choice is explicit, and it is an environment variable rather than a config key
 * because logback initialises before the config file is read: one source of truth, resolved
 * the same way {@code HS_LOG_DIR} already is.
 *
 * <pre>
 *   HS_AGENT_LOG_IDENTIFIERS=masked   (default) agent.log is not written at all
 *   HS_AGENT_LOG_IDENTIFIERS=raw      agent.log carries IMSI / MSISDN / ICCID in clear
 * </pre>
 *
 * <p>Anything else is a configuration error and fails at startup, because the alternative -
 * silently falling back to the safe value - leaves an operator who typed {@code RAW} believing
 * the opposite of what is happening.
 */
public final class IdentifierLogging {

    public static final String ENV = "HS_AGENT_LOG_IDENTIFIERS";

    /** Identities never reach a file. The default. */
    public static final String MASKED = "masked";

    /** {@code agent.log} carries raw identities. Owner-only, on the device, rolled off quickly. */
    public static final String RAW = "raw";

    private IdentifierLogging() {
    }

    /**
     * Reads the setting from the environment, then from a system property of the same name
     * (tests, and a wrapper that cannot export variables).
     *
     * @throws IllegalArgumentException when the value is neither {@code masked} nor {@code raw}
     */
    public static String configured() {
        String value = System.getenv(ENV);
        if (value == null || value.isBlank()) {
            value = System.getProperty(ENV);
        }
        if (value == null || value.isBlank()) {
            return MASKED;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.equals(MASKED) && !normalized.equals(RAW)) {
            throw new IllegalArgumentException(
                    ENV + " must be \"" + MASKED + "\" or \"" + RAW + "\", got \"" + value + "\"");
        }
        return normalized;
    }

    /** Whether raw identifiers may be written. Fails closed: an unreadable setting means masked. */
    public static boolean rawEnabled() {
        try {
            return RAW.equals(configured());
        } catch (IllegalArgumentException _) {
            return false;
        }
    }
}
