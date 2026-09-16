package com.hushsign.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SchemaValidationTest {

    private static ProtocolValidator validator;

    @BeforeAll
    static void setUp() {
        validator = new ProtocolValidator();
    }

    @Test
    void goldenEmissionCommandIsValid() {
        assertDoesNotThrow(() -> validator.validate(MessageType.EMISSION_COMMAND,
                GoldenPayloads.load("emission-command.json")));
    }

    @Test
    void goldenEmissionCancelledIsValid() {
        assertDoesNotThrow(() -> validator.validate(MessageType.EMISSION_CANCELLED,
                GoldenPayloads.load("emission-cancelled.json")));
    }

    @Test
    void goldenTransmissionResultIsValid() {
        assertDoesNotThrow(() -> validator.validate(MessageType.TRANSMISSION_RESULT,
                GoldenPayloads.load("transmission-result.json")));
    }

    @Test
    void goldenDeliveryReportIsValid() {
        assertDoesNotThrow(() -> validator.validate(MessageType.DELIVERY_REPORT,
                GoldenPayloads.load("delivery-report.json")));
    }

    @Test
    void goldenGatewayHeartbeatIsValid() {
        assertDoesNotThrow(() -> validator.validate(MessageType.GATEWAY_HEARTBEAT,
                GoldenPayloads.load("gateway-heartbeat.json")));
    }

    @Test
    void goldenAuditDigestIsValid() {
        assertDoesNotThrow(() -> validator.validate(MessageType.AUDIT_DIGEST,
                GoldenPayloads.load("audit-digest.json")));
    }

    @Test
    void unknownFieldsAreIgnored() {
        ObjectNode node = (ObjectNode) ProtocolJson.tree(
                GoldenPayloads.load("emission-command.json"));
        node.put("futureField", "tolerated");
        assertDoesNotThrow(() -> validator.validate(MessageType.EMISSION_COMMAND, node));
    }

    @Test
    void missingRequiredFieldIsRejected() {
        JsonNode node = ProtocolJson.tree(
                GoldenPayloads.load("emission-command.json"));
        ((ObjectNode) node).remove("msisdn");
        assertThrows(ProtocolValidationException.class,
                () -> validator.validate(MessageType.EMISSION_COMMAND, node));
    }

    @Test
    void unknownTechniqueIsRejected() {
        ObjectNode node = (ObjectNode) ProtocolJson.tree(
                GoldenPayloads.load("emission-command.json"));
        node.put("technique", "ARBITRARY_PAYLOAD");
        assertThrows(ProtocolValidationException.class,
                () -> validator.validate(MessageType.EMISSION_COMMAND, node));
    }

    @Test
    void malformedUuidIsRejected() {
        ObjectNode node = (ObjectNode) ProtocolJson.tree(
                GoldenPayloads.load("emission-command.json"));
        node.put("emissionId", "not-a-uuid");
        assertThrows(ProtocolValidationException.class,
                () -> validator.validate(MessageType.EMISSION_COMMAND, node));
    }

    @Test
    void malformedMsisdnIsRejected() {
        ObjectNode node = (ObjectNode) ProtocolJson.tree(
                GoldenPayloads.load("emission-command.json"));
        node.put("msisdn", "phone:+40 722 111 222");
        assertThrows(ProtocolValidationException.class,
                () -> validator.validate(MessageType.EMISSION_COMMAND, node));
    }

    @Test
    void malformedOperatorIsRejected() {
        ObjectNode node = (ObjectNode) ProtocolJson.tree(
                GoldenPayloads.load("emission-command.json"));
        node.put("operator", "Vodafone RO");
        assertThrows(ProtocolValidationException.class,
                () -> validator.validate(MessageType.EMISSION_COMMAND, node));
    }

    @Test
    void unsupportedProtocolVersionIsRejected() {
        ObjectNode node = (ObjectNode) ProtocolJson.tree(
                GoldenPayloads.load("emission-command.json"));
        node.put("protocolVersion", "2");
        assertThrows(ProtocolValidationException.class,
                () -> validator.validate(MessageType.EMISSION_COMMAND, node));
    }

    @Test
    void unknownTransmissionOutcomeIsRejected() {
        ObjectNode node = (ObjectNode) ProtocolJson.tree(
                GoldenPayloads.load("transmission-result.json"));
        node.put("outcome", "MAYBE");
        assertThrows(ProtocolValidationException.class,
                () -> validator.validate(MessageType.TRANSMISSION_RESULT, node));
    }

    @Test
    void heartbeatWithRawIccidIsRejected() {
        ObjectNode node = (ObjectNode) ProtocolJson.tree(
                GoldenPayloads.load("gateway-heartbeat.json"));
        ((ObjectNode) node.get("sims").get(0)).put("iccidHash", "8944010000000000000");
        assertThrows(ProtocolValidationException.class,
                () -> validator.validate(MessageType.GATEWAY_HEARTBEAT, node));
    }

    @Test
    void heartbeatWithNegativeCounterIsRejected() {
        ObjectNode node = (ObjectNode) ProtocolJson.tree(
                GoldenPayloads.load("gateway-heartbeat.json"));
        ((ObjectNode) node.get("counters")).put("transmissionsRejected", -1);
        assertThrows(ProtocolValidationException.class,
                () -> validator.validate(MessageType.GATEWAY_HEARTBEAT, node));
    }

    @Test
    void digestWithShortChainHeadIsRejected() {
        ObjectNode node = (ObjectNode) ProtocolJson.tree(
                GoldenPayloads.load("audit-digest.json"));
        node.put("chainHead", "deadbeef");
        assertThrows(ProtocolValidationException.class,
                () -> validator.validate(MessageType.AUDIT_DIGEST, node));
    }
}
