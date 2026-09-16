package com.hushsign.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandValidatorTest {

    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");

    private static final CommandValidator VALIDATOR = new CommandValidator();

    private static String commandIssuedAt(Instant issuedAt) {
        ObjectNode node = (ObjectNode) ProtocolJson.tree(
                GoldenPayloads.load("emission-command.json"));
        node.put("issuedAt", issuedAt.toString());
        return ProtocolJson.write(node);
    }

    private static String cancelledIssuedAt(Instant issuedAt) {
        ObjectNode node = (ObjectNode) ProtocolJson.tree(
                GoldenPayloads.load("emission-cancelled.json"));
        node.put("issuedAt", issuedAt.toString());
        return ProtocolJson.write(node);
    }

    @Test
    void freshEmissionCommandIsAccepted() {
        String json = commandIssuedAt(NOW.minusSeconds(10));
        assertDoesNotThrow(() -> VALIDATOR.validate(MessageType.EMISSION_COMMAND, json, NOW));
    }

    @Test
    void staleEmissionCommandIsRefused() {
        String json = commandIssuedAt(NOW.minusSeconds(300 + 121));
        CommandValidator.StaleCommandException e = assertThrows(
                CommandValidator.StaleCommandException.class,
                () -> VALIDATOR.validate(MessageType.EMISSION_COMMAND, json, NOW));
        assertEquals(FreshnessGate.Freshness.STALE, e.freshness());
    }

    @Test
    void clockSkewedEmissionCommandIsRefused() {
        String json = commandIssuedAt(NOW.plusSeconds(121));
        CommandValidator.StaleCommandException e = assertThrows(
                CommandValidator.StaleCommandException.class,
                () -> VALIDATOR.validate(MessageType.EMISSION_COMMAND, json, NOW));
        assertEquals(FreshnessGate.Freshness.CLOCK_SKEW, e.freshness());
    }

    @Test
    void freshCancellationIsAccepted() {
        String json = cancelledIssuedAt(NOW.minusSeconds(5));
        assertDoesNotThrow(() -> VALIDATOR.validate(MessageType.EMISSION_CANCELLED, json, NOW));
    }

    @Test
    void staleCancellationIsRefused() {
        String json = cancelledIssuedAt(NOW.minusSeconds(300 + 121));
        assertThrows(CommandValidator.StaleCommandException.class,
                () -> VALIDATOR.validate(MessageType.EMISSION_CANCELLED, json, NOW));
    }

    @Test
    void expiryAfterIssueIsEnforced() {
        ObjectNode node = (ObjectNode) ProtocolJson.tree(
                GoldenPayloads.load("emission-command.json"));
        node.put("issuedAt", NOW.minusSeconds(10).toString());
        node.put("expiresAt", NOW.minusSeconds(20).toString()); // expiresAt before issuedAt
        assertThrows(IllegalArgumentException.class,
                () -> VALIDATOR.validate(MessageType.EMISSION_COMMAND, ProtocolJson.write(node), NOW));
    }

    @Test
    void attemptAboveMaxAttemptsIsRejected() {
        ObjectNode node = (ObjectNode) ProtocolJson.tree(
                GoldenPayloads.load("emission-command.json"));
        node.put("attempt", 4); // maxAttempts = 3
        node.put("issuedAt", NOW.minusSeconds(10).toString());
        assertThrows(IllegalArgumentException.class,
                () -> VALIDATOR.validate(MessageType.EMISSION_COMMAND, ProtocolJson.write(node), NOW));
    }

    @Test
    void nonCommandTypesAreRejected() {
        JsonNode heartbeat = ProtocolJson.tree(
                GoldenPayloads.load("gateway-heartbeat.json"));
        assertThrows(IllegalArgumentException.class,
                () -> VALIDATOR.validate(MessageType.GATEWAY_HEARTBEAT, ProtocolJson.write(heartbeat), NOW));
    }
}
