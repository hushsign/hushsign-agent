package com.hushsign.agent.runtime.kafka;

import com.hushsign.agent.runtime.policy.PolicyDecision;
import com.hushsign.agent.runtime.policy.PolicyProvider;
import com.hushsign.protocol.v1.EmissionCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * P1-S11: the local policy gate in front of the execution pipeline.
 *
 * <p>Wraps the {@link EmissionHandler} chain: an emission is forwarded to the
 * delegate only after {@link PolicyProvider#evaluate} allows it. A rejection
 * — or a provider exception, treated fail-closed as a rejection — is dropped
 * here with a counter, so nothing reaches a modem. P7 reports this path as an
 * explicit {@code BLOCKED_BY_POLICY} transmission instead of only counting.
 */
public final class PolicyCheckingEmissionHandler implements EmissionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(PolicyCheckingEmissionHandler.class);

    private final PolicyProvider policy;
    private final EmissionHandler delegate;
    private final AtomicLong policyRejections = new AtomicLong();

    public PolicyCheckingEmissionHandler(PolicyProvider policy, EmissionHandler delegate) {
        this.policy = policy;
        this.delegate = delegate;
    }

    @Override
    public void onCommand(EmissionCommand command) throws Exception {
        PolicyDecision decision = decisionFor(command);
        if (decision.allowed()) {
            delegate.onCommand(command);
        } else {
            policyRejections.incrementAndGet();
            LOG.warn("policy rejected emission {}: {}", command.emissionId(), decision.reason());
        }
    }

    @Override
    public void onRejected(String rawPayload, EmissionConsumer.RejectReason reason) {
        delegate.onRejected(rawPayload, reason);
    }

    /** Number of emissions the policy gate dropped (fail-closed). */
    public long policyRejectionCount() {
        return policyRejections.get();
    }

    private PolicyDecision decisionFor(EmissionCommand command) {
        try {
            return policy.evaluate(command);
        } catch (RuntimeException e) {
            LOG.error("policy provider failed for emission {} - failing closed", command.emissionId(), e);
            return PolicyDecision.reject("policy provider failed: " + e.getMessage());
        }
    }
}
