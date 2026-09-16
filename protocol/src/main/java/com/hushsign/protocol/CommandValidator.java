package com.hushsign.protocol;

import com.hushsign.protocol.v1.EmissionCommand;
import com.hushsign.protocol.v1.EmissionCancelled;

import java.time.Instant;

/**
 * Full validation of inbound commands on the agent: JSON Schema first,
 * then the freshness gate on {@code issuedAt}, then cross-field rules.
 *
 * <p>A command that fails the freshness gate is refused on arrival — an agent
 * that was offline cannot execute stale work beyond the max command age.
 */
public final class CommandValidator {

    private final ProtocolValidator protocolValidator;
    private final FreshnessGate freshnessGate;

    public CommandValidator() {
        this(new ProtocolValidator(), new FreshnessGate());
    }

    public CommandValidator(FreshnessGate freshnessGate) {
        this(new ProtocolValidator(), freshnessGate);
    }

    public CommandValidator(ProtocolValidator protocolValidator, FreshnessGate freshnessGate) {
        this.protocolValidator = protocolValidator;
        this.freshnessGate = freshnessGate;
    }

    /**
     * Validates a command payload against its schema, the freshness gate and
     * cross-field rules, evaluated at {@code now}.
     *
     * @throws ProtocolValidationException if the payload violates its schema
     * @throws StaleCommandException if the freshness gate refuses the command
     * @throws IllegalArgumentException if cross-field rules fail
     */
    public void validate(MessageType type, String json, Instant now) {
        if (!type.isCommand()) {
            throw new IllegalArgumentException(type + " is not a command type");
        }
        protocolValidator.validate(type, json);
        FreshnessGate.Freshness freshness =
                freshnessGate.check(issuedAt(type, json), now);
        if (freshness != FreshnessGate.Freshness.FRESH) {
            throw new StaleCommandException(type, freshness);
        }
        if (type == MessageType.EMISSION_COMMAND) {
            EmissionCommandRules.check(ProtocolJson.read(json, EmissionCommand.class));
        }
    }

    private static Instant issuedAt(MessageType type, String json) {
        return switch (type) {
            case EMISSION_COMMAND -> ProtocolJson.read(json, EmissionCommand.class).issuedAt();
            case EMISSION_CANCELLED -> ProtocolJson.read(json, EmissionCancelled.class).issuedAt();
            default -> throw new IllegalArgumentException(type + " is not a command type");
        };
    }

    /** A command refused by the freshness gate. */
    public static final class StaleCommandException extends RuntimeException {
        private final FreshnessGate.Freshness freshness;

        public StaleCommandException(MessageType type, FreshnessGate.Freshness freshness) {
            super(type + " refused by freshness gate: " + freshness);
            this.freshness = freshness;
        }

        public FreshnessGate.Freshness freshness() {
            return freshness;
        }
    }
}
