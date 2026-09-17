package com.hushsign.agent.runtime.policy;

import com.hushsign.protocol.v1.EmissionCommand;

/**
 * P1-S11: the local policy gate every emission must pass before anything
 * reaches a modem — "no send without policy pass" (plan §1.1 principle 6,
 * §5.2 fail-closed guarantees, anti-exfiltration guarantee G3).
 *
 * <p>Contract: implementations must return a decision for every command and
 * must not throw. Callers that need fail-closed behaviour treat a provider
 * exception as {@link PolicyDecision#reject(String)} — never as an allow.
 *
 * <p>The full customer-owned engine (technique/operator allowlists,
 * per-target/per-SIM/per-gateway caps, quiet hours, kill switch, plan §5.3)
 * lands in P7-S1. Until then the agent runs on
 * {@link AcceptAllPolicyProvider}; this interface is the stable seam, so the
 * swap touches wiring only.
 */
@FunctionalInterface
public interface PolicyProvider {

    /**
     * Evaluates one emission against the local policy.
     *
     * @param command the validated emission command (schema + freshness gate
     *                already passed upstream)
     * @return allow or reject with a non-blank reason
     */
    PolicyDecision evaluate(EmissionCommand command);
}
