package com.hushsign.agent.runtime.cancel;

import com.hushsign.protocol.CommandValidator;
import com.hushsign.protocol.MessageType;
import com.hushsign.protocol.ProtocolJson;
import com.hushsign.protocol.Topics;
import com.hushsign.protocol.v1.EmissionCancelled;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P1-S13: consumes {@code hs.emissions.cancelled.v1} and feeds the
 * {@link CancellationStore} that {@link CancellingEmissionHandler} consults.
 *
 * <p><b>Broadcast, not competing consumers.</b> The cancellation topic is keyed
 * by {@code emissionId}, and <em>every</em> gateway must learn about every
 * cancellation (the emission could be queued on any of them). So the consumer
 * group is per gateway — {@code gw-cancel-{gatewayId}} — unlike the emission
 * topic, where gateways of one operator compete in {@code gw-{operator}}.
 *
 * <p>Cancellations pass the same protocol gate as emissions (schema + freshness
 * gate). A stale cancellation is dropped: the emission it targets is older than
 * {@code maxCommandAgeSeconds} and is refused by the emission-side freshness
 * gate anyway.
 */
public final class CancellationConsumer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(CancellationConsumer.class);

    private final Consumer<String, String> consumer;
    private final String groupId;
    private final CancellationStore cancellations;
    private final CommandValidator validator;
    private final Clock clock;

    private final AtomicLong applied = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    private Thread pollThread;

    public CancellationConsumer(String bootstrapServers, String gatewayId,
                                CancellationStore cancellations) {
        this(buildConsumer(bootstrapServers, gatewayId), gatewayId, cancellations,
                new CommandValidator(), Clock.systemUTC());
    }

    public CancellationConsumer(Consumer<String, String> consumer, String gatewayId,
                                CancellationStore cancellations,
                                CommandValidator validator, Clock clock) {
        this.consumer = consumer;
        this.groupId = Topics.cancelGroup(gatewayId);
        this.cancellations = cancellations;
        this.validator = validator;
        this.clock = clock;
        this.consumer.subscribe(List.of(Topics.EMISSIONS_CANCELLED));
    }

    /**
     * Properties of the cancellation consumer. Package-visible so tests can
     * assert the per-gateway group and the offset policy without a broker.
     */
    static Properties consumerProperties(String bootstrapServers, String gatewayId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, Topics.cancelGroup(gatewayId));
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        // Older cancellations are useless (the emissions they target are stale too):
        // a fresh gateway must not replay the whole retention window on first start.
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "50");
        return props;
    }

    private static KafkaConsumer<String, String> buildConsumer(String bootstrapServers, String gatewayId) {
        return new KafkaConsumer<>(consumerProperties(bootstrapServers, gatewayId));
    }

    /** The consumer group of this gateway. */
    public String groupId() {
        return groupId;
    }

    public long appliedCount() {
        return applied.get();
    }

    public long refusedCount() {
        return refused.get();
    }

    /** Starts the background poll loop. Tests can call {@link #pollOnce} directly instead. */
    public synchronized void start() {
        if (pollThread != null && pollThread.isAlive()) {
            return;
        }
        closed.set(false);
        pollThread = new Thread(this::pollLoop, "hs-agent-cancel-consumer");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void pollLoop() {
        while (!closed.get()) {
            try {
                pollOnce(Duration.ofMillis(500));
            } catch (WakeupException e) {
                break;
            } catch (RuntimeException e) {
                LOG.error("cancellation poll iteration failed; continuing", e);
            }
        }
    }

    /**
     * Polls once, records every valid cancellation, then commits. An invalid or
     * stale record is terminal (logged and committed); a store failure aborts
     * the batch so the cancellation is redelivered.
     */
    public int pollOnce(Duration timeout) {
        ConsumerRecords<String, String> records = consumer.poll(timeout);
        boolean batchHandled = true;
        for (var record : records) {
            String raw = record.value();
            EmissionCancelled cancelled;
            try {
                validator.validate(MessageType.EMISSION_CANCELLED, raw, Instant.now(clock));
                cancelled = ProtocolJson.read(raw, EmissionCancelled.class);
            } catch (RuntimeException e) {
                refused.incrementAndGet();
                LOG.warn("discarding cancellation record: {}", e.getMessage());
                continue;
            }
            try {
                cancellations.cancel(cancelled.emissionId());
                applied.incrementAndGet();
            } catch (RuntimeException e) {
                LOG.error("cannot record cancellation for {}; leaving the batch uncommitted",
                        cancelled.emissionId(), e);
                batchHandled = false;
                break;
            }
        }
        if (batchHandled && records.count() > 0) {
            consumer.commitSync(Duration.ofSeconds(5));
        }
        return records.count();
    }

    @Override
    public synchronized void close() {
        if (closed.getAndSet(true)) {
            return;
        }
        consumer.wakeup();
        if (pollThread != null && pollThread != Thread.currentThread()) {
            try {
                pollThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        consumer.close();
    }
}
