package com.hushsign.protocol;

import com.hushsign.protocol.v1.EmissionCommand;

/**
 * Cross-field rules of {@link EmissionCommand} that JSON Schema cannot express.
 */
public final class EmissionCommandRules {

    private EmissionCommandRules() {
    }

    public static void check(EmissionCommand command) {
        if (command.attempt() < 1) {
            throw new IllegalArgumentException("attempt must be >= 1, got " + command.attempt());
        }
        if (command.maxAttempts() < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got " + command.maxAttempts());
        }
        if (command.attempt() > command.maxAttempts()) {
            throw new IllegalArgumentException(
                    "attempt " + command.attempt() + " exceeds maxAttempts " + command.maxAttempts());
        }
        // Invariant 3: an emission can never be executed after its expiry.
        if (!command.expiresAt().isAfter(command.issuedAt())) {
            throw new IllegalArgumentException(
                    "expiresAt must be after issuedAt: " + command.issuedAt() + " -> " + command.expiresAt());
        }
    }
}
