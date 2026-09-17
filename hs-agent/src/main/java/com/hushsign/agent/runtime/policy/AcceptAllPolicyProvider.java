package com.hushsign.agent.runtime.policy;

import com.hushsign.protocol.v1.EmissionCommand;

/**
 * P1-S11 development stub: allows every emission.
 *
 * <p>Deliberately permissive — the simulator and the early E2E wiring need no
 * policy. The full engine (P7-S1) replaces this provider; the interface is
 * the stable seam, so the swap touches wiring only.
 */
public final class AcceptAllPolicyProvider implements PolicyProvider {

    /** Fixed reason so logs stay diffable while the stub is in place. */
    public static final String REASON =
            "allowed by accept-all policy stub (full engine arrives in P7)";

    @Override
    public PolicyDecision evaluate(EmissionCommand command) {
        return PolicyDecision.allow(REASON);
    }
}
