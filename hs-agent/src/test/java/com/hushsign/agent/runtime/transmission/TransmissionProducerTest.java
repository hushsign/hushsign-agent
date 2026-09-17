package com.hushsign.agent.runtime.transmission;

import com.hushsign.agent.runtime.heartbeat.Hashes;
import com.hushsign.agent.runtime.spool.Spool;
import com.hushsign.agent.runtime.spool.SpooledRecord;
import com.hushsign.protocol.ProtocolJson;
import com.hushsign.protocol.ProtocolValidationException;
import com.hushsign.protocol.Topics;
import com.hushsign.protocol.v1.EmissionCommand;
import com.hushsign.protocol.v1.TransmissionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-S13: every execution outcome becomes one schema-valid, spooled
 * transmission on {@code hs.transmissions.v1} keyed by emissionId.
 */
class TransmissionProducerTest {

    private static final Instant NOW = Instant.parse("2026-09-17T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path tmp;

    private Spool spool;
    private TransmissionProducer producer;

    @BeforeEach
    void setUp() {
        spool = Spool.open(tmp.resolve("spool.jsonl"));
        producer = new TransmissionProducer("gw-1", "modem-1", "sim-1", spool, CLOCK);
    }

    private static EmissionCommand command() {
        return new EmissionCommand(
                "1", "5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e",
                "b4c1a2d3-84f5-4a4c-8c0f-112233445566", "w-2026-0142", "t-8842",
                "+40722111222", "SILENT_TP0", "226-10", 1, 3,
                NOW.minusSeconds(10), NOW.plusSeconds(600));
    }

    private SpooledRecord lastSpooled() {
        return spool.peekAll().get(spool.size() - 1);
    }

    private TransmissionResult lastParsed() {
        return ProtocolJson.read(lastSpooled().value(), TransmissionResult.class);
    }

    @Test
    void happyPathSpoolsAValidTransmission() {
        TransmissionResult reported = producer.report(
                command(), TransmissionResult.Outcome.ACCEPTED, null, "0011AA22", NOW.minusSeconds(5));

        assertEquals(1, spool.size());
        SpooledRecord record = lastSpooled();
        assertEquals(Topics.TRANSMISSIONS, record.topic());
        assertEquals("5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e", record.key());

        TransmissionResult parsed = lastParsed();
        assertEquals("1", parsed.protocolVersion());
        assertEquals(reported.transmissionId(), parsed.transmissionId());
        assertEquals("5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e", parsed.emissionId());
        assertEquals(1, parsed.attempt());
        assertEquals("ACCEPTED", parsed.outcome());
        assertNull(parsed.failureCause());
        assertEquals(Hashes.sha256("0011AA22"), parsed.pduSha256());
        assertEquals("226-10", parsed.operatorRef());
        assertEquals("gw-1", parsed.gatewayId());
        assertEquals("modem-1", parsed.modemId());
        assertEquals("sim-1", parsed.simId());
        assertEquals(NOW.minusSeconds(5), parsed.requestedAt());
        assertEquals(NOW, parsed.completedAt());
    }

    @Test
    void failureCarriesCauseAndPduEvidence() {
        String cause = "+CMS ERROR: 500";

        TransmissionResult reported = producer.report(
                command(), TransmissionResult.Outcome.FAILED, cause, "0011AA22", NOW.minusSeconds(5));

        TransmissionResult parsed = lastParsed();
        assertEquals("FAILED", parsed.outcome());
        assertEquals(cause, parsed.failureCause());
        assertEquals(Hashes.sha256("0011AA22"), parsed.pduSha256());
        assertEquals(reported.transmissionId(), parsed.transmissionId());
    }

    @Test
    void causeIsTruncatedToTheWireLimit() {
        producer.report(command(), TransmissionResult.Outcome.FAILED, "x".repeat(300), null, NOW);

        TransmissionResult parsed = lastParsed();
        assertEquals(255, parsed.failureCause().length());
        assertTrue(parsed.failureCause().chars().allMatch(c -> c == 'x'));
    }

    @Test
    void invalidOutcomeNeverReachesTheSpool() {
        assertThrows(ProtocolValidationException.class, () ->
                producer.report(command(), "MAYBE", null, null, NOW));
        assertEquals(0, spool.size());
    }
}
