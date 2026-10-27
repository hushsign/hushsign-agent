package com.hushsign.agent.runtime.kafka;

import com.hushsign.agent.runtime.audit.AuditLog;
import com.hushsign.agent.runtime.audit.NoopAuditLog;
import com.hushsign.agent.runtime.policy.PolicyDecision;
import com.hushsign.agent.runtime.policy.PolicyProvider;
import com.hushsign.agent.runtime.sensitive.SensitiveRedactor;
import com.hushsign.agent.runtime.transmission.TransmissionRecorder;
import com.hushsign.protocol.v1.EmissionCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P1-S11: the local policy gate in front of the execution pipeline.
 *
 * <p>Wraps the {@link EmissionHandler} chain: an emission is forwarded to the
 * delegate only after {@link PolicyProvider#evaluate} allows it. A rejection —
 * or a provider exception, treated fail-closed as a rejection — produces a
 * {@code BLOCKED_BY_POLICY} transmission and never reaches a modem, so the
 * platform settles the emission on evidence instead of waiting for a retry to
 * time out.
 */
public final class PolicyCheckingEmissionHandler implements EmissionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(PolicyCheckingEmissionHandler.class);

    private final PolicyProvider policy;
    private final EmissionHandler delegate;
    private final TransmissionRecorder recorder;
    private final AuditLog audit;
    private final Clock clock;
    private final AtomicLong policyRejections = new AtomicLong();

    /** Without an audit log the gate still works; the runtime always passes one (P7-S2). */
    public PolicyCheckingEmissionHandler(PolicyProvider policy, EmissionHandler delegate,
                                         TransmissionRecorder recorder, Clock clock) {
        this(policy, delegate, recorder, NoopAuditLog.INSTANCE, clock);
    }

    public PolicyCheckingEmissionHandler(PolicyProvider policy, EmissionHandler delegate,
                                         TransmissionRecorder recorder, AuditLog audit, Clock clock) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void onCommand(EmissionCommand command) throws Exception {
        PolicyDecision decision = decisionFor(command);
        if (decision.allowed()) {
            audit.record("POLICY_ALLOW", command.emissionId(), decision.reason());
            delegate.onCommand(command);
            return;
        }
        policyRejections.incrementAndGet();
        LOG.warn("policy rejected emission {}: {}", command.emissionId(), decision.reason());
        // The chain on this device may know why in full; the message that leaves for the platform
        // may not. The cap reason names the target number, and SECURITY.md promises that
        // hs.transmissions.v1 carries no target number - so scrub before recording, not later
        // when the platform projects it.
        audit.record("POLICY_REJECT", command.emissionId(), decision.reason());
        recorder.record(command, "BLOCKED_BY_POLICY", SensitiveRedactor.scrub(decision.reason()),
                Instant.now(clock));
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
