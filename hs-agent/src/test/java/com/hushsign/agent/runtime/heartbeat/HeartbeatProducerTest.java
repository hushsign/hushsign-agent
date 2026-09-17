package com.hushsign.agent.runtime.heartbeat;

import com.hushsign.agent.runtime.spool.Spool;
import com.hushsign.agent.runtime.spool.SpooledRecord;
import com.hushsign.protocol.MessageType;
import com.hushsign.protocol.ProtocolJson;
import com.hushsign.protocol.ProtocolValidator;
import com.hushsign.protocol.Topics;
import com.hushsign.protocol.v1.GatewayHeartbeat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-S9 tests: heartbeat payload shape, schema validity, sequence, spool
 * integration and input validation.
 */
class HeartbeatProducerTest {

    private static final Instant NOW = Instant.parse("2026-09-17T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final ProtocolValidator validator = new ProtocolValidator();

    @TempDir
    Path tempDir;

    private Spool spool;
    private Map<String, Long> counters;
    private List<GatewayHeartbeat.ModemInfo> modems;
    private List<GatewayHeartbeat.SimInfo> sims;
    private HeartbeatProducer producer;

    @BeforeEach
    void setUp() {
        spool = Spool.open(tempDir.resolve("spool.jsonl"));
        counters = new HashMap<>(Map.of("transmissionsAccepted", 2L, "transmissionsRejected", 0L));
        modems = List.of(new GatewayHeartbeat.ModemInfo(
                "SIMCOM-7600E-0001", "SIM7600E", "SIMCom",
                GatewayHeartbeat.ModemInfo.Status.READY,
                "/dev/serial/by-id/usb-SIMCOM_SIM7600E-0001", "SM", 18));
        sims = List.of(new GatewayHeartbeat.SimInfo(
                "89abcdef0123456789abcdef0123456789abcdef0123456789abcdef01234567",
                "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
                "226-10", "primary", true, "SIMCOM-7600E-0001", "+40722••••22"));
        producer = new HeartbeatProducer(source(), spool, CLOCK);
    }

    private HeartbeatSource source() {
        return new HeartbeatSource() {
            @Override
            public String gatewayId() {
                return "gw-buc-01";
            }

            @Override
            public String agentVersion() {
                return "0.1.0";
            }

            @Override
            public String policyHash() {
                return "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";
            }

            @Override
            public String configHash() {
                return "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
            }

            @Override
            public Long uptimeSeconds() {
                return 3600L;
            }

            @Override
            public List<GatewayHeartbeat.ModemInfo> modems() {
                return modems;
            }

            @Override
            public List<GatewayHeartbeat.SimInfo> sims() {
                return sims;
            }

            @Override
            public Map<String, Long> counters() {
                return counters;
            }
        };
    }

    @Test
    void pulseSpoolsASchemaValidHeartbeatToTheFleetTopic() {
        GatewayHeartbeat heartbeat = producer.pulse();

        assertEquals(1, spool.size());
        SpooledRecord record = spool.peekAll().get(0);
        assertEquals(Topics.FLEET_HEARTBEAT, record.topic());
        assertEquals("gw-buc-01", record.key(), "heartbeats are keyed by gatewayId");

        GatewayHeartbeat parsed = ProtocolJson.read(record.value(), GatewayHeartbeat.class);
        assertEquals(heartbeat, parsed, "wire round-trip");
        validator.validate(MessageType.GATEWAY_HEARTBEAT, record.value());
    }

    @Test
    void sequenceCountsUpPerPulse() {
        assertEquals(0, producer.sequence());
        GatewayHeartbeat first = producer.pulse();
        GatewayHeartbeat second = producer.pulse();

        assertEquals(1, first.sequence());
        assertEquals(2, second.sequence());
        assertEquals(2, spool.size());
    }

    @Test
    void heartbeatCarriesModemsSimsCountersAndHashes() {
        GatewayHeartbeat heartbeat = producer.pulse();

        assertEquals("gw-buc-01", heartbeat.gatewayId());
        assertEquals("0.1.0", heartbeat.version());
        assertEquals("fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210", heartbeat.policyHash());
        assertEquals("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", heartbeat.configHash());
        assertEquals(3600L, heartbeat.uptimeSeconds());
        assertEquals(NOW, heartbeat.sentAt());
        assertEquals(1, heartbeat.modems().size());
        assertEquals(GatewayHeartbeat.ModemInfo.Status.READY, heartbeat.modems().get(0).status());
        assertEquals("+40722••••22", heartbeat.sims().get(0).msisdnMasked());
        assertEquals(2L, heartbeat.counters().get("transmissionsAccepted"));
    }

    @Test
    void malformedHashesAreRefusedBeforeSpooling() {
        HeartbeatProducer badPolicy = new HeartbeatProducer(new HeartbeatSource() {
            @Override
            public String gatewayId() {
                return "gw-buc-01";
            }

            @Override
            public String agentVersion() {
                return "0.1.0";
            }

            @Override
            public String policyHash() {
                return "not-a-hash";
            }

            @Override
            public String configHash() {
                return "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
            }

            @Override
            public Long uptimeSeconds() {
                return null;
            }

            @Override
            public List<GatewayHeartbeat.ModemInfo> modems() {
                return List.of();
            }

            @Override
            public List<GatewayHeartbeat.SimInfo> sims() {
                return List.of();
            }

            @Override
            public Map<String, Long> counters() {
                return Map.of();
            }
        }, spool, CLOCK);

        assertThrows(IllegalArgumentException.class, badPolicy::pulse);
        assertEquals(0, spool.size(), "nothing may be spooled when validation fails");
    }

    @Test
    void negativeCountersAreRefused() {
        counters.put("emissionsExpired", -1L);
        assertThrows(IllegalArgumentException.class, () -> producer.pulse());
        assertEquals(0, spool.size());
    }

    @Test
    void spooledHeartbeatsFlushInOrderThroughTheSpool() {
        producer.pulse();
        producer.pulse();

        List<String> sent = new ArrayList<>();
        int flushed = spool.flushTo(record -> sent.add(record.value()));

        assertEquals(2, flushed);
        assertTrue(ProtocolJson.read(sent.get(0), GatewayHeartbeat.class).sequence()
                < ProtocolJson.read(sent.get(1), GatewayHeartbeat.class).sequence());
        assertEquals(0, spool.size());
    }
}
