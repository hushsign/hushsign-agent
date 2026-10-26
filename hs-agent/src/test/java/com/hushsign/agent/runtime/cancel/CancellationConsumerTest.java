package com.hushsign.agent.runtime.cancel;

import com.hushsign.protocol.CommandValidator;
import com.hushsign.protocol.ProtocolJson;
import com.hushsign.protocol.Topics;
import com.hushsign.protocol.v1.EmissionCancelled;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-S13: the cancellation consumer is a broadcast consumer (one group per
 * gateway, never the competing {@code gw-{operator}} group) and it feeds the
 * store the executor consults.
 */
class CancellationConsumerTest {

    private static final Instant NOW = Instant.parse("2026-09-17T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String GATEWAY = "gw-buc-01";
    private static final String ID = "5c9f7a2e-66f1-4b1f-9e7f-1f2a3b4c5d6e";

    private final TopicPartition tp0 = new TopicPartition(Topics.EMISSIONS_CANCELLED, 0);

    @TempDir
    Path tempDir;

    private CancellationStore store;
    private MockConsumer<String, String> kafka;
    private CancellationConsumer consumer;

    @BeforeEach
    void setUp() {
        store = CancellationStore.open(tempDir.resolve("cancellations.ids"), 100);
        kafka = new MockConsumer<>(OffsetResetStrategy.LATEST);
        kafka.updateBeginningOffsets(Map.of(tp0, 0L));
        kafka.updateEndOffsets(Map.of(tp0, 0L));
        consumer = new CancellationConsumer(kafka, GATEWAY, store, new CommandValidator(), CLOCK);
        kafka.rebalance(java.util.List.of(tp0));
    }

    private static String json(String emissionId, Instant issuedAt) {
        return ProtocolJson.write(new EmissionCancelled("1", emissionId, issuedAt));
    }

    private void deliver(String payload, long offset) {
        kafka.addRecord(new ConsumerRecord<>(Topics.EMISSIONS_CANCELLED, 0, offset, ID, payload));
    }

    @Test
    void subscribesBroadcastWithAPerGatewayGroup() {
        assertEquals(Set.of(Topics.EMISSIONS_CANCELLED), kafka.subscription());
        assertEquals("gw-cancel-" + GATEWAY, consumer.groupId(),
                "every gateway must see every cancellation");
    }

    @Test
    void consumerPropertiesUseTheGatewayGroupAndSkipOldRecords() {
        Properties props = CancellationConsumer.consumerProperties("kafka:9092", GATEWAY);

        assertEquals("gw-cancel-" + GATEWAY, props.get(ConsumerConfig.GROUP_ID_CONFIG));
        assertEquals("false", props.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG));
        assertEquals("latest", props.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG));
    }

    @Test
    void aFreshCancellationIsRecordedAndCommitted() {
        deliver(json(ID, NOW.minusSeconds(10)), 0);

        assertEquals(1, consumer.pollOnce(Duration.ZERO));
        assertTrue(store.isCancelled(ID));
        assertEquals(1, consumer.appliedCount());
        assertEquals(1L, kafka.committed(tp0).offset());
    }

    @Test
    void aStaleCancellationIsDroppedNotApplied() {
        deliver(json(ID, NOW.minusSeconds(600)), 0);

        assertEquals(1, consumer.pollOnce(Duration.ZERO));
        assertFalse(store.isCancelled(ID), "a stale cancellation cannot be trusted");
        assertEquals(1, consumer.refusedCount());
        assertEquals(1L, kafka.committed(tp0).offset(), "refusals are terminal");
    }

    @Test
    void anUnparseableRecordIsDropped() {
        deliver("{not json", 0);

        assertEquals(1, consumer.pollOnce(Duration.ZERO));
        assertEquals(0, store.size());
        assertEquals(1, consumer.refusedCount());
    }
}
