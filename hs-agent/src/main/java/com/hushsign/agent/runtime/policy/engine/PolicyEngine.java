package com.hushsign.agent.runtime.policy.engine;

import com.hushsign.agent.runtime.policy.PolicyDecision;
import com.hushsign.agent.runtime.policy.PolicyProvider;
import com.hushsign.agent.runtime.policy.RuntimeState;
import com.hushsign.protocol.v1.EmissionCommand;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The customer-owned policy engine (P7-S1): the gate every emission passes before anything
 * reaches a modem.
 *
 * <p>The checks run cheapest-and-most-final first, so the reason a command was refused is
 * the most specific one available:
 *
 * <ol>
 *   <li>kill switch - nothing is sent, whatever the command says;</li>
 *   <li>freshness - a command that has expired, or is older than the policy allows, could
 *       be a replay or a stuck queue;</li>
 *   <li>operator and technique allow lists;</li>
 *   <li>quiet hours, in the deployment's own time zone;</li>
 *   <li>hourly caps per target, per operator and per gateway.</li>
 * </ol>
 *
 * <p>Fail-closed is the whole point: a malformed command (no technique, no operator, no
 * target) is refused rather than sent, and a counting ceiling is checked against the count
 * <em>including</em> the command being judged, so the last allowed send cannot become the
 * first one over the line.
 *
 * <p>Thread-safety: the engine is used from every consumer thread; the only mutable state is
 * the {@link HourlyCounter}, which is concurrent.
 */
public final class PolicyEngine implements PolicyProvider {

    private final String gatewayId;
    private final HourlyCounter counter;
    private final Clock clock;
    private final RuntimeState state;

    /**
     * Volatile rather than final since P7-S4: the admin API can reload the file, and every
     * consumer thread must see the new rules on its next command. The swap is a single
     * reference assignment, so no command can ever be judged against half a policy.
     */
    private volatile PolicySettings settings;

    public PolicyEngine(PolicySettings settings, String gatewayId, HourlyCounter counter, Clock clock) {
        this(settings, gatewayId, counter, clock, RuntimeState.RUNNING);
    }

    public PolicyEngine(PolicySettings settings, String gatewayId, HourlyCounter counter, Clock clock,
                        RuntimeState state) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.gatewayId = Objects.requireNonNull(gatewayId, "gatewayId");
        this.counter = Objects.requireNonNull(counter, "counter");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.state = Objects.requireNonNull(state, "state");
    }

    /** Replaces the rules in force. Counters are deliberately kept: a reload must not reset a cap. */
    public void applyPolicy(PolicySettings reloaded) {
        this.settings = Objects.requireNonNull(reloaded, "reloaded");
    }

    public PolicySettings settings() {
        return this.settings;
    }

    @Override
    public PolicyDecision evaluate(EmissionCommand command) {
        if (command == null) {
            return PolicyDecision.reject("no command");
        }
        if (this.state.killed() || settings.killSwitch()) {
            return PolicyDecision.reject("kill switch engaged");
        }
        if (this.state.paused()) {
            return PolicyDecision.reject("agent is paused by the operator");
        }
        PolicyDecision freshness = freshness(command);
        if (!freshness.allowed()) {
            return freshness;
        }
        if (isBlank(command.operator())) {
            return PolicyDecision.reject("command names no operator");
        }
        if (!settings.allowedOperators().isEmpty() && !settings.allowedOperators().contains(command.operator())) {
            return PolicyDecision.reject("operator " + command.operator() + " is not in the allow list");
        }
        if (isBlank(command.technique())) {
            return PolicyDecision.reject("command names no technique");
        }
        if (!settings.allowedTechniques().isEmpty() && !settings.allowedTechniques().contains(command.technique())) {
            return PolicyDecision.reject("technique " + command.technique() + " is not in the allow list");
        }
        if (isBlank(command.msisdn())) {
            return PolicyDecision.reject("command names no target number");
        }
        PolicyDecision quiet = quietHours();
        if (!quiet.allowed()) {
            return quiet;
        }
        return caps(command);
    }

    private PolicyDecision freshness(EmissionCommand command) {
        Instant now = clock.instant();
        if (command.expiresAt() != null && now.isAfter(command.expiresAt())) {
            return PolicyDecision.reject("command expired at " + command.expiresAt());
        }
        if (command.issuedAt() == null) {
            return PolicyDecision.reject("command has no issue time, so it cannot be shown to be fresh");
        }
        Duration maxAge = settings.maxCommandAge();
        if (maxAge != null && command.issuedAt().plus(maxAge).isBefore(now)) {
            return PolicyDecision.reject("command is older than the " + maxAge.toSeconds()
                    + "s freshness limit (issued " + command.issuedAt() + ")");
        }
        if (command.issuedAt().isAfter(now.plus(Duration.ofMinutes(5)))) {
            // A command from the future is a clock problem or a forgery; either way, not ours.
            return PolicyDecision.reject("command is issued in the future (" + command.issuedAt() + ")");
        }
        return PolicyDecision.allow("fresh");
    }

    private PolicyDecision quietHours() {
        PolicySettings.QuietHours window = settings.quietHours();
        if (window == null) {
            return PolicyDecision.allow("no quiet hours configured");
        }
        LocalTime now = ZonedDateTime.ofInstant(clock.instant(), window.zone()).toLocalTime();
        boolean inside = window.start().isBefore(window.end())
                ? !now.isBefore(window.start()) && now.isBefore(window.end())
                : !now.isBefore(window.start()) || now.isBefore(window.end());
        if (inside) {
            return PolicyDecision.reject("quiet hours " + window.start() + "-" + window.end()
                    + " " + window.zone());
        }
        return PolicyDecision.allow("outside quiet hours");
    }

    private PolicyDecision caps(EmissionCommand command) {
        PolicySettings.Caps caps = settings.caps();
        List<String> counted = new ArrayList<>();
        PolicyDecision decision = underCap(caps.perTargetPerHour(), "target " + command.msisdn(),
                "target:" + command.msisdn(), counted);
        if (decision.allowed()) {
            decision = underCap(caps.perOperatorPerHour(), "operator " + command.operator(),
                    "operator:" + command.operator(), counted);
        }
        if (decision.allowed()) {
            decision = underCap(caps.perGatewayPerHour(), "gateway " + gatewayId, "gateway:" + gatewayId, counted);
        }
        if (!decision.allowed()) {
            counted.forEach(counter::decrement);
        }
        return decision;
    }

    private PolicyDecision underCap(int ceiling, String what, String key, List<String> counted) {
        if (ceiling <= 0) {
            return PolicyDecision.allow("no cap for " + what);
        }
        long count = counter.incrementAndGet(key);
        if (count > ceiling) {
            return PolicyDecision.reject(what + " has reached its " + ceiling + "-per-hour cap");
        }
        counted.add(key);
        return PolicyDecision.allow("under the " + what + " cap");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
