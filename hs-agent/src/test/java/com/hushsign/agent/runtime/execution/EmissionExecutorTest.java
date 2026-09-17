package com.hushsign.agent.runtime.execution;

import com.hushsign.agent.runtime.heartbeat.Hashes;
import com.hushsign.agent.runtime.spool.Spool;
import com.hushsign.agent.runtime.transmission.TransmissionProducer;
import com.hushsign.protocol.ProtocolJson;
import com.hushsign.protocol.v1.EmissionCommand;
import com.hushsign.protocol.v1.TransmissionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-S13: the terminal handler turns a validated emission into exactly one
 * transmission — expiry and build errors short-circuit before any send.
 */
class EmissionExecutorTest {

    private static final Instant NOW = Instant.parse("2026-09-17T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path tmp;

    private Spool spool;
    private EmissionExecutor executor;
    private AtomicReference<String> sentPdu;

    @BeforeEach
    void setUp() {
        spool = Spool.open(tmp.resolve("spool.jsonl"));
        TransmissionProducer producer =
                new TransmissionProducer("gw-1", "modem-1", "sim-1", spool, CLOCK);
        sentPdu = new AtomicReference<>();
        executor = new EmissionExecutor(pdu -> {
            sentPdu.set(pdu);
            return 42;
        }, producer, CLOCK);
    }

    private static EmissionCommand command(String emissionId, Instant expiresAt) {
        return command(emissionId, expiresAt, "SILENT_TP0");
    }

    private static EmissionCommand command(String emissionId, Instant expiresAt, String technique) {
        return new EmissionCommand(
                "1", emissionId, "b4c1a2d3-84f5-4a4c-8c0f-112233445566", "w-2026-0142", "t-8842",
                "+40722111222", technique, "226-10", 1, 3,
                NOW.minusSeconds(10), expiresAt);
    }

    private EmissionCommand freshCommand(String emissionId) {
        return command(emissionId, NOW.plusSeconds(600));
    }

    private TransmissionResult lastResult() {
        String json = spool.peekAll().get(spool.size() - 1).value();
        return ProtocolJson.read(json, TransmissionResult.class);
    }

    @Test
    void acceptedSendBecomesAnAcceptedTransmission() {
        executor.onCommand(freshCommand("5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e"));

        TransmissionResult result = lastResult();
        assertEquals("ACCEPTED", result.outcome());
        assertEquals(1, result.attempt());
        assertEquals(NOW, result.requestedAt());
        assertEquals(NOW, result.completedAt());
        assertTrue(sentPdu.get().startsWith("00")); // SMS-SUBMIT with empty SMSC octet
        assertEquals(Hashes.sha256(sentPdu.get()), result.pduSha256()); // evidence matches the send
    }

    @Test
    void expiredEmissionIsNeverSent() {
        executor.onCommand(command("5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e", NOW.minusSeconds(1)));

        TransmissionResult result = lastResult();
        assertEquals("EXPIRED", result.outcome());
        assertNull(result.failureCause());
        assertNull(result.pduSha256());
        assertNull(sentPdu.get()); // nothing reached the modem
    }

    @Test
    void sendFailureBecomesAFailedTransmissionWithEvidence() {
        TransmissionProducer producer =
                new TransmissionProducer("gw-1", "modem-1", "sim-1", spool, CLOCK);
        executor = new EmissionExecutor(pdu -> {
            throw new IOException("+CMS ERROR: 500");
        }, producer, CLOCK);

        executor.onCommand(freshCommand("5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e"));

        TransmissionResult result = lastResult();
        assertEquals("FAILED", result.outcome());
        assertEquals("+CMS ERROR: 500", result.failureCause());
        assertTrue(result.pduSha256() != null); // the attempted PDU is evidence
    }

    @Test
    void unknownTechniqueFailsWithoutTouchingTheModem() {
        EmissionCommand unknown = new EmissionCommand(
                "1", "5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e",
                "b4c1a2d3-84f5-4a4c-8c0f-112233445566", "w-2026-0142", "t-8842",
                "+40722111222", "LASER_PING", "226-10", 1, 3,
                NOW.minusSeconds(10), NOW.plusSeconds(600));

        executor.onCommand(unknown);

        TransmissionResult result = lastResult();
        assertEquals("FAILED", result.outcome());
        assertEquals("unknown technique LASER_PING", result.failureCause());
        assertNull(sentPdu.get());
    }

    @Test
    void unusableNumberFailsWithoutTouchingTheModem() {
        EmissionCommand badNumber = new EmissionCommand(
                "1", "5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e",
                "b4c1a2d3-84f5-4a4c-8c0f-112233445566", "w-2026-0142", "t-8842",
                "+4072X", "SILENT_TP0", "226-10", 1, 3,
                NOW.minusSeconds(10), NOW.plusSeconds(600));

        executor.onCommand(badNumber);

        TransmissionResult result = lastResult();
        assertEquals("FAILED", result.outcome());
        assertTrue(result.failureCause().startsWith("cannot build PDU:"));
        assertNull(sentPdu.get());
    }

    @Test
    void allSixCatalogTechniquesExecuteWithoutParams() {
        String[] techniques = {"SILENT_TP0", "WAP_PUSH_EMPTY", "WAP_PUSH_SL",
                "WAP_PUSH_SI", "MWI_TOGGLE", "MMS_NOTIFY_EMPTY"};
        String[] ids = {"00000000-0000-4000-8000-000000000001",
                "00000000-0000-4000-8000-000000000002",
                "00000000-0000-4000-8000-000000000003",
                "00000000-0000-4000-8000-000000000004",
                "00000000-0000-4000-8000-000000000005",
                "00000000-0000-4000-8000-000000000006"};
        for (int i = 0; i < techniques.length; i++) {
            executor.onCommand(command(ids[i], NOW.plusSeconds(600), techniques[i]));
        }

        assertEquals(techniques.length, spool.size());
        for (var record : spool.peekAll()) {
            TransmissionResult result = ProtocolJson.read(record.value(), TransmissionResult.class);
            assertEquals("ACCEPTED", result.outcome());
        }
    }
}
