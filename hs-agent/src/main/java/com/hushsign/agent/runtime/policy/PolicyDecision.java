package com.hushsign.agent.runtime.policy;

import java.util.Objects;

/**
 * P1-S11: the outcome of one {@link PolicyProvider} evaluation.
 *
 * <p>The P7 policy engine will reuse this shape for every verdict, so the
 * seam never changes when the engine lands. Reasons stay non-null so the
 * audit chain (P7-S2) can always record why an emission was refused.
 *
 * @param allowed {@code true} when the emission may proceed toward a modem
 * @param reason  why the decision was taken (logs; audit entries in P7)
 */
public record PolicyDecision(boolean allowed, String reason) {

    public PolicyDecision {
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }

    /** An allow decision, e.g. {@code PolicyDecision.allow("technique in allowlist")}. */
    public static PolicyDecision allow(String reason) {
        return new PolicyDecision(true, reason);
    }

    /** A reject decision, e.g. {@code PolicyDecision.reject("quiet hours 23:00-06:00")}. */
    public static PolicyDecision reject(String reason) {
        return new PolicyDecision(false, reason);
    }
}
