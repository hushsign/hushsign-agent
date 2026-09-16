package com.hushsign.agent.runtime.kafka;

import com.hushsign.protocol.CommandValidator;
import com.hushsign.protocol.ProtocolJson;
import com.hushsign.protocol.Topics;
import com.hushsign.protocol.v1.EmissionCommand;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-S6 tests: per-operator subscription, manual commit, pause/resume for
 * serial operations and the freshness gate, driven through a MockConsumer.
 */
class EmissionConsumerTest {

    private static final String OPERATOR = "226-10";
    private static final String TOPIC = "hs.emissions.226-10";
    private static final Instant NOW = Instant.parse("2026-09-16T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final TopicPartition tp0 = new TopicPartition(TOPIC, 0);
    private MockConsumer<String, String> kafka;
    private List<EmissionCommand> commands;
    private List<String> rejectedPayloads;
    private List<EmissionConsumer.RejectReason> rejectedReasons;
    private AtomicInteger failuresBeforeSuccess;
    private EmissionConsumer consumer;

    @BeforeEach
    void setUp() {
        kafka = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        kafka.updateBeginningOffsets(Map.of(tp0, 0L));
        commands = new ArrayList<>();
        rejectedPayloads = new ArrayList<>();
        rejectedReasons = new ArrayList<>();
        failuresBeforeSuccess = new AtomicInteger();
        consumer = new EmissionConsumer(kafka, OPERATOR, new EmissionHandler() {
            @Override
            public void onCommand(EmissionCommand command) {
                if (failuresBeforeSuccess.getAndDecrement() > 0) {
                    throw new IllegalStateException("simulated handler failure");
                }
                commands.add(command);
            }

            @Override
            public void onRejected(String rawPayload, EmissionConsumer.RejectReason reason) {
                rejectedPayloads.add(rawPayload);
                rejectedReasons.add(reason);
            }
        }, new CommandValidator(), CLOCK);
        kafka.rebalance(List.of(tp0)); // the group leader assigns the topic partition
    }

    private static String commandJson(String emissionId, Instant issuedAt, Instant expiresAt) {
        return ProtocolJson.write(new EmissionCommand(
                "1", emissionId, "b4c1a2d3-84f5-4a4c-8c0f-112233445566", "w-2026-0142", "t-8842",
                "+40722111222", "SILENT_TP0", OPERATOR, 1, 3, issuedAt, expiresAt));
    }

    private void deliver(String json, long offset) {
        kafka.addRecord(new ConsumerRecord<>(TOPIC, 0, offset, "40722111222", json));
    }

    @Test
    void subscribesToTheOperatorTopicWithTheGatewayGroup() {
        assertEquals(Set.of(TOPIC), kafka.subscription());
        assertEquals(TOPIC, consumer.topic());
        assertEquals("gw-" + OPERATOR, consumer.groupId());
    }

    @Test
    void deliversFreshCommandsAndCommitsTheBatch() {
        String json = commandJson("5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e", NOW.minusSeconds(10), NOW.plusSeconds(600));
        deliver(json, 0);

        assertEquals(1, consumer.pollOnce(Duration.ZERO));
        assertEquals(1, commands.size());
        assertEquals("5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e", commands.get(0).emissionId());
        assertEquals(1, consumer.deliveredCount());
        assertEquals(0, rejectedPayloads.size());
        assertEquals(1L, kafka.committed(tp0).offset(), "batch must be committed after handling");
    }

    @Test
    void staleCommandsAreRejectedAndCommitted() {
        // older than maxCommandAge (300 s) + skew (120 s)
        String stale = commandJson("5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e", NOW.minusSeconds(500), NOW.plusSeconds(600));
        deliver(stale, 0);

        assertEquals(1, consumer.pollOnce(Duration.ZERO));
        assertEquals(0, commands.size(), "stale commands never reach the handler");
        assertEquals(List.of(stale), rejectedPayloads);
        assertEquals(List.of(EmissionConsumer.RejectReason.STALE), rejectedReasons);
        assertEquals(1, consumer.rejectedCount());
        assertEquals(1L, kafka.committed(tp0).offset(), "rejections are terminal and committed");
    }

    @Test
    void futureDatedCommandsAreClockSkew() {
        String skew = commandJson("5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e", NOW.plusSeconds(200), NOW.plusSeconds(600));
        deliver(skew, 0);

        consumer.pollOnce(Duration.ZERO);
        assertEquals(List.of(EmissionConsumer.RejectReason.CLOCK_SKEW), rejectedReasons);
    }

    @Test
    void malformedPayloadsAreRejectedAsInvalid() {
        deliver("{not-json", 0);

        consumer.pollOnce(Duration.ZERO);
        assertEquals(0, commands.size());
        assertEquals(List.of(EmissionConsumer.RejectReason.INVALID), rejectedReasons);
    }

    @Test
    void schemaViolationsAreRejectedAsInvalid() {
        deliver("{\"protocolVersion\":\"1\"}", 0); // missing required fields

        consumer.pollOnce(Duration.ZERO);
        assertEquals(0, commands.size());
        assertEquals(List.of(EmissionConsumer.RejectReason.INVALID), rejectedReasons);
    }

    @Test
    void handlerFailureLeavesTheBatchUncommitted() {
        failuresBeforeSuccess.set(1);
        String json = commandJson("5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e", NOW.minusSeconds(10), NOW.plusSeconds(600));
        deliver(json, 0);

        assertEquals(1, consumer.pollOnce(Duration.ZERO));
        assertNull(kafka.committed(tp0), "no commit after a handler failure");
        assertEquals(0, consumer.deliveredCount());

        // a real broker redelivers the uncommitted batch after a rebalance
        kafka.seek(tp0, 0);
        deliver(json, 0);
        assertEquals(1, consumer.pollOnce(Duration.ZERO));
        assertEquals(1, commands.size());
        assertEquals(1L, kafka.committed(tp0).offset());
    }

    @Test
    void pauseStopsDeliveryAndResumeRestoresIt() {
        deliver(commandJson("5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e", NOW.minusSeconds(10), NOW.plusSeconds(600)), 0);

        consumer.pause();
        assertTrue(consumer.isPaused());
        assertEquals(0, consumer.pollOnce(Duration.ZERO), "paused partitions deliver nothing");

        consumer.resume();
        assertFalse(consumer.isPaused());
        assertEquals(1, consumer.pollOnce(Duration.ZERO));
        assertEquals(1, commands.size());
    }

    @Test
    void closeClosesTheUnderlyingConsumer() {
        consumer.close();
        assertTrue(consumerClosed());
    }

    private boolean consumerClosed() {
        try {
            kafka.position(tp0);
            return false;
        } catch (IllegalStateException e) {
            return e.getMessage() != null && e.getMessage().contains("closed");
        }
    }
}
