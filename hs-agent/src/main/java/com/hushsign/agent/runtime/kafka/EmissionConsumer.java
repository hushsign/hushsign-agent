package com.hushsign.agent.runtime.kafka;

import com.hushsign.protocol.CommandValidator;
import com.hushsign.protocol.MessageType;
import com.hushsign.protocol.ProtocolJson;
import com.hushsign.protocol.Topics;
import com.hushsign.protocol.v1.EmissionCommand;
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
 * P1-S6: the agent's per-operator Kafka consumer layer.
 *
 * <ul>
 *   <li>Subscribes to {@code hs.emissions.{operator}} with consumer group
 *       {@code gw-{operator}} (competing gateways of one operator).</li>
 *   <li><b>Manual commit</b>: offsets are committed only after the whole
 *       batch was processed; a handler failure leaves the batch uncommitted
 *       (at-least-once redelivery — the P1-S7 dedupe store absorbs it).</li>
 *   <li><b>Pause/resume</b>: {@link #pause()} requests pausing all assigned
 *       partitions (applied on the polling thread); the broker keeps the
 *       session alive, so long serial work never triggers a rebalance.</li>
 *   <li><b>Freshness gate</b>: every payload passes
 *       {@link CommandValidator} (JSON Schema → freshness gate → cross-field
 *       rules); stale/skewed commands are rejected, never executed.</li>
 * </ul>
 *
 * <p>One instance per operator. Single-threaded: polling, validation and the
 * handler all run on one thread (or on the test thread via {@link #pollOnce}).
 */
public final class EmissionConsumer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(EmissionConsumer.class);

    /** Why a payload never reached the handler. */
    public enum RejectReason {
        /** Unparseable JSON, schema violation or cross-field rule failure. */
        INVALID,
        /** Freshness gate: command older than maxCommandAge (+ skew). */
        STALE,
        /** Freshness gate: command dated beyond the skew allowance in the future. */
        CLOCK_SKEW
    }

    private final Consumer<String, String> consumer;
    private final String operator;
    private final EmissionHandler handler;
    private final CommandValidator validator;
    private final Clock clock;

    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicBoolean pauseRequested = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private Thread pollThread;

    public EmissionConsumer(String bootstrapServers, String operator, EmissionHandler handler) {
        this(buildConsumer(bootstrapServers, operator), operator, handler,
                new CommandValidator(), Clock.systemUTC());
    }

    public EmissionConsumer(Consumer<String, String> consumer, String operator,
                            EmissionHandler handler, CommandValidator validator, Clock clock) {
        this.consumer = consumer;
        this.operator = operator;
        this.handler = handler;
        this.validator = validator;
        this.clock = clock;
        this.consumer.subscribe(List.of(Topics.emissions(operator)));
    }

    private static KafkaConsumer<String, String> buildConsumer(String bootstrapServers, String operator) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, Topics.gatewayGroup(operator));
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "10");
        return new KafkaConsumer<>(props);
    }

    /** The emission topic this consumer serves. */
    public String topic() {
        return Topics.emissions(operator);
    }

    /** The competing gateway consumer group of the operator. */
    public String groupId() {
        return Topics.gatewayGroup(operator);
    }

    /** Requests pausing all assigned partitions; applied on the polling thread. */
    public void pause() {
        pauseRequested.set(true);
    }

    /** Resumes delivery after {@link #pause()}. */
    public void resume() {
        pauseRequested.set(false);
    }

    public boolean isPaused() {
        return pauseRequested.get();
    }

    public long deliveredCount() {
        return delivered.get();
    }

    public long rejectedCount() {
        return rejected.get();
    }

    /** Starts the background poll loop. Tests can call {@link #pollOnce} directly instead. */
    public synchronized void start() {
        if (pollThread != null && pollThread.isAlive()) {
            return;
        }
        closed.set(false);
        pollThread = new Thread(this::pollLoop, "hs-agent-consumer-" + operator);
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void pollLoop() {
        try {
            while (!closed.get()) {
                pollOnce(Duration.ofMillis(500));
            }
        } catch (WakeupException e) {
            // close() asked us to stop
        }
    }

    /**
     * Polls once, validates and dispatches every record, then commits the
     * batch when fully handled. Rejected records are terminal and committed;
     * a handler failure aborts the batch without committing (redelivery).
     */
    public int pollOnce(Duration timeout) {
        applyPauseState();
        ConsumerRecords<String, String> records = consumer.poll(timeout);
        int count = records.count();
        boolean batchHandled = true;
        for (var record : records) {
            if (closed.get()) {
                batchHandled = false;
                break;
            }
            String raw = record.value();
            try {
                validator.validate(MessageType.EMISSION_COMMAND, raw, Instant.now(clock));
            } catch (CommandValidator.StaleCommandException e) {
                RejectReason reason = e.freshness() == com.hushsign.protocol.FreshnessGate.Freshness.CLOCK_SKEW
                        ? RejectReason.CLOCK_SKEW
                        : RejectReason.STALE;
                LOG.warn("discarding {} record on {}: {}", reason, topic(), e.getMessage());
                handler.onRejected(raw, reason);
                rejected.incrementAndGet();
                continue;
            } catch (RuntimeException e) {
                LOG.warn("discarding invalid record on {}: {}", topic(), e.getMessage());
                handler.onRejected(raw, RejectReason.INVALID);
                rejected.incrementAndGet();
                continue;
            }
            try {
                handler.onCommand(ProtocolJson.read(raw, EmissionCommand.class));
                delivered.incrementAndGet();
            } catch (Exception e) {
                LOG.error("handler failed on {}; batch left uncommitted", topic(), e);
                batchHandled = false;
                break;
            }
        }
        if (batchHandled && count > 0) {
            consumer.commitSync(Duration.ofSeconds(5));
        }
        return count;
    }

    private void applyPauseState() {
        if (consumer.assignment().isEmpty()) {
            return;
        }
        if (pauseRequested.get()) {
            consumer.pause(consumer.assignment());
        } else {
            consumer.resume(consumer.assignment());
        }
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
