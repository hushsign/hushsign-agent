package com.hushsign.protocol;

import com.hushsign.protocol.v1.AuditDigest;
import com.hushsign.protocol.v1.DeliveryReport;
import com.hushsign.protocol.v1.EmissionCancelled;
import com.hushsign.protocol.v1.EmissionCommand;
import com.hushsign.protocol.v1.GatewayHeartbeat;
import com.hushsign.protocol.v1.TransmissionResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class WireRecordsTest {

    private static final ProtocolValidator VALIDATOR = new ProtocolValidator();

    @Test
    void emissionCommandRoundTrips() {
        EmissionCommand command = ProtocolJson.read(GoldenPayloads.load("emission-command.json"),
                EmissionCommand.class);
        assertEquals("1", command.protocolVersion());
        assertEquals("5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e", command.emissionId());
        assertEquals("SILENT_TP0", command.technique());
        assertEquals("+40722111222", command.msisdn());
        assertEquals("226-10", command.operator());
        assertEquals(Instant.parse("2026-09-10T12:00:00Z"), command.issuedAt());
        assertEquals(Instant.parse("2026-09-10T12:10:00Z"), command.expiresAt());

        // serialize → still schema-valid
        String json = ProtocolJson.write(command);
        VALIDATOR.validate(MessageType.EMISSION_COMMAND, json);
    }

    @Test
    void unknownFieldIsIgnoredOnRead() {
        String withExtra = GoldenPayloads.load("emission-command.json")
                .replace("}", ",\"extraField\":{\"anything\":true}}");
        EmissionCommand command = ProtocolJson.read(withExtra, EmissionCommand.class);
        assertEquals("w-2026-0142", command.warrantRef());
    }

    @Test
    void transmissionResultRoundTrips() {
        TransmissionResult result = ProtocolJson.read(
                GoldenPayloads.load("transmission-result.json"), TransmissionResult.class);
        assertEquals(TransmissionResult.Outcome.ACCEPTED, result.outcome());
        assertEquals("gw-buc-01", result.gatewayId());
        VALIDATOR.validate(MessageType.TRANSMISSION_RESULT, ProtocolJson.write(result));
    }

    @Test
    void deliveryReportRoundTrips() {
        DeliveryReport report = ProtocolJson.read(
                GoldenPayloads.load("delivery-report.json"), DeliveryReport.class);
        assertEquals(DeliveryReport.Status.DELIVERED, report.status());
        VALIDATOR.validate(MessageType.DELIVERY_REPORT, ProtocolJson.write(report));
    }

    @Test
    void gatewayHeartbeatRoundTrips() {
        GatewayHeartbeat heartbeat = ProtocolJson.read(
                GoldenPayloads.load("gateway-heartbeat.json"), GatewayHeartbeat.class);
        assertEquals("gw-buc-01", heartbeat.gatewayId());
        assertEquals(41, heartbeat.sequence());
        assertNotNull(heartbeat.modems());
        assertEquals(1, heartbeat.modems().size());
        assertEquals("SIMCOM-7600E-0001", heartbeat.modems().get(0).serial());
        assertEquals(GatewayHeartbeat.ModemInfo.Status.READY, heartbeat.modems().get(0).status());
        assertEquals(1, heartbeat.sims().size());
        assertEquals("+40722••••22", heartbeat.sims().get(0).msisdnMasked());
        assertEquals(2L, heartbeat.counters().get("transmissionsAccepted"));
        VALIDATOR.validate(MessageType.GATEWAY_HEARTBEAT, ProtocolJson.write(heartbeat));
    }

    @Test
    void auditDigestRoundTrips() {
        AuditDigest digest = ProtocolJson.read(
                GoldenPayloads.load("audit-digest.json"), AuditDigest.class);
        assertEquals(12, digest.entryCount());
        assertEquals("gw-buc-01", digest.gatewayId());
        VALIDATOR.validate(MessageType.AUDIT_DIGEST, ProtocolJson.write(digest));
    }

    @Test
    void emissionCancelledRoundTrips() {
        EmissionCancelled cancelled = ProtocolJson.read(
                GoldenPayloads.load("emission-cancelled.json"), EmissionCancelled.class);
        assertEquals("5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e", cancelled.emissionId());
        VALIDATOR.validate(MessageType.EMISSION_CANCELLED, ProtocolJson.write(cancelled));
    }

    @Test
    void topicsDerivePerOperatorNames() {
        assertEquals("hs.emissions.226-10", Topics.emissions("226-10"));
        assertEquals("gw-226-10", Topics.gatewayGroup("226-10"));
    }
}
