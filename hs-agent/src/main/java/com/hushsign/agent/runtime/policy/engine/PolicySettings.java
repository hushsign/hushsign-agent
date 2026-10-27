package com.hushsign.agent.runtime.policy.engine;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

/**
 * The local policy the agent enforces on every emission (P7-S1), read from
 * {@code policy.yaml}.
 *
 * <p>An empty allow list means "everything of that kind" only because the alternative - an
 * empty list meaning "nothing" - is a silent kill switch. The kill switch is explicit
 * ({@link #killSwitch}), and so is every other refusal: a deployment that wants to allow
 * one technique says so, and the reason for a rejection can always be read back to an
 * auditor.
 *
 * @param killSwitch        when true the agent refuses every emission until it is restarted
 *                          with a policy that allows them
 * @param allowedTechniques techniques this gateway may send; empty means any
 * @param allowedOperators  operators this gateway may send on; empty means any
 * @param caps              per-hour ceilings; a zero cap means "no ceiling"
 * @param quietHours        the window in which nothing is sent; null when there is none
 * @param maxCommandAge     how old a command may be when it arrives; null leaves the
 *                          decision to the command's own {@code expiresAt}
 */
public record PolicySettings(
        boolean killSwitch,
        Set<String> allowedTechniques,
        Set<String> allowedOperators,
        Caps caps,
        QuietHours quietHours,
        Duration maxCommandAge) {

    public PolicySettings {
        allowedTechniques = allowedTechniques == null ? Set.of() : Set.copyOf(allowedTechniques);
        allowedOperators = allowedOperators == null ? Set.of() : Set.copyOf(allowedOperators);
        caps = caps == null ? Caps.UNLIMITED : caps;
    }

    /** Per-hour ceilings. Zero is "no ceiling"; a negative value is a configuration error. */
    public record Caps(int perTargetPerHour, int perOperatorPerHour, int perGatewayPerHour) {

        public static final Caps UNLIMITED = new Caps(0, 0, 0);

        public Caps {
            if (perTargetPerHour < 0 || perOperatorPerHour < 0 || perGatewayPerHour < 0) {
                throw new IllegalArgumentException("caps cannot be negative");
            }
        }
    }

    /**
     * A window during which the agent sends nothing.
     *
     * @param start the local time the window opens (inclusive)
     * @param end   the local time it closes (exclusive); may be earlier than {@code start}
     *              for a window that crosses midnight
     * @param zone  the zone the times are expressed in (UTC when absent)
     */
    public record QuietHours(LocalTime start, LocalTime end, ZoneId zone) {

        public QuietHours {
            if (start == null || end == null) {
                throw new IllegalArgumentException("quiet hours need a start and an end");
            }
            if (start.equals(end)) {
                throw new IllegalArgumentException("quiet hours start and end must differ");
            }
            zone = zone == null ? ZoneId.of("UTC") : zone;
        }
    }
}
