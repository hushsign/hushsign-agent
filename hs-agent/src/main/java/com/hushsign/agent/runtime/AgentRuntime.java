package com.hushsign.agent.runtime;

import com.hushsign.agent.config.AgentConfig;
import com.hushsign.agent.gsm.modem.Modem;
import com.hushsign.agent.runtime.cancel.CancellationConsumer;
import com.hushsign.agent.runtime.cancel.CancellationStore;
import com.hushsign.agent.runtime.cancel.CancellingEmissionHandler;
import com.hushsign.agent.runtime.dedupe.DedupeStore;
import com.hushsign.agent.runtime.execution.EmissionExecutor;
import com.hushsign.agent.runtime.heartbeat.Hashes;
import com.hushsign.agent.runtime.heartbeat.HeartbeatProducer;
import com.hushsign.agent.runtime.heartbeat.HeartbeatSource;
import com.hushsign.agent.runtime.kafka.DedupingEmissionHandler;
import com.hushsign.agent.runtime.kafka.EmissionConsumer;
import com.hushsign.agent.runtime.kafka.EmissionHandler;
import com.hushsign.agent.runtime.kafka.KafkaSpoolSender;
import com.hushsign.agent.runtime.kafka.PolicyCheckingEmissionHandler;
import com.hushsign.agent.runtime.policy.AcceptAllPolicyProvider;
import com.hushsign.agent.runtime.spool.Spool;
import com.hushsign.agent.runtime.transmission.TransmissionProducer;
import com.hushsign.protocol.v1.GatewayHeartbeat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P1-S13: the production composition root of the agent — one modem, the full
 * handler chain and every Kafka consumer/heartbeat thread.
 *
 * <p>Chain per emission (plan §5.2, order matters):
 * <pre>
 * EmissionConsumer (schema → freshness gate)
 *   → dedupe (emissionId:attempt claim, persisted)
 *   → cancellation (drop work the platform cancelled, report CANCELLED)
 *   → policy (accept-all stub until P7-S1; refusal reported BLOCKED_BY_POLICY)
 *   → executor (expiry → technique → PDU → modem, report the outcome)
 *   → spool → hs.transmissions.v1
 * </pre>
 *
 * <p>Before this class existed the pipeline was wired only inside the
 * simulator module, so the "real" runtime would have been written twice. The
 * simulator now starts this same runtime with a mock modem.
 */
public final class AgentRuntime implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AgentRuntime.class);

    private final String gatewayId;
    private final Spool spool;
    private final KafkaSpoolSender sender;
    private final List<EmissionConsumer> consumers;
    private final CancellationConsumer cancellationConsumer;
    private final HeartbeatProducer heartbeat;
    private final long heartbeatIntervalMs;
    private final Modem modem;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong flushed = new AtomicLong();
    private final Instant startedAt = Instant.now();
    private final AtomicBoolean flusherRunning = new AtomicBoolean();
    private final AtomicBoolean heartbeatRunning = new AtomicBoolean();
    private Thread flusher;
    private Thread heartbeatThread;
    private Thread shutdownHook;

    private AgentRuntime(String gatewayId, Spool spool, KafkaSpoolSender sender,
                         List<EmissionConsumer> consumers, CancellationConsumer cancellationConsumer,
                         HeartbeatProducer heartbeat, long heartbeatIntervalMs, Modem modem) {
        this.gatewayId = gatewayId;
        this.spool = spool;
        this.sender = sender;
        this.consumers = consumers;
        this.cancellationConsumer = cancellationConsumer;
        this.heartbeat = heartbeat;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.modem = modem;
    }

    /**
     * Builds the runtime around an already-open modem and starts every thread.
     *
     * @param config       parsed {@code agent.yaml}
     * @param configFile   the file the config came from (hash goes into the heartbeat), or {@code null}
     * @param modem        the claimed modem (real serial port or simulator)
     * @param agentVersion version reported in the heartbeat
     */
    public static AgentRuntime start(AgentConfig config, Path configFile, Modem modem,
                                     String agentVersion) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(modem, "modem");

        Path workDir = Path.of(config.workDir()).toAbsolutePath();
        ensureDirectory(workDir);

        Spool spool = Spool.open(workDir.resolve(config.spool().path()).normalize());
        DedupeStore dedupe = DedupeStore.open(
                workDir.resolve("dedupe.ids"), config.dedupe().maxEntries());
        CancellationStore cancels = CancellationStore.open(
                workDir.resolve("cancellations.ids"), config.dedupe().maxEntries());

        String bootstrap = config.kafka().bootstrapServers();
        KafkaSpoolSender sender = new KafkaSpoolSender(bootstrap);
        TransmissionProducer transmissions = new TransmissionProducer(
                config.gatewayId(), serialOf(modem), null, spool);

        EmissionHandler chain = new DedupingEmissionHandler(dedupe,
                new CancellingEmissionHandler(cancels,
                        new PolicyCheckingEmissionHandler(new AcceptAllPolicyProvider(),
                                new EmissionExecutor(modem::sendPdu, transmissions),
                                transmissions, Clock.systemUTC()),
                        transmissions, Clock.systemUTC()));

        List<EmissionConsumer> consumers = new ArrayList<>();
        for (String operator : config.operators()) {
            consumers.add(new EmissionConsumer(bootstrap, operator, chain));
        }
        CancellationConsumer cancellationConsumer =
                new CancellationConsumer(bootstrap, config.gatewayId(), cancels);

        HeartbeatSource source = new RuntimeHeartbeatSource(config, configFile, modem, consumers,
                Instant.now(), agentVersion);
        HeartbeatProducer heartbeat = new HeartbeatProducer(source, spool);

        AgentRuntime runtime = new AgentRuntime(config.gatewayId(), spool, sender, consumers,
                cancellationConsumer, heartbeat, config.heartbeat().intervalSeconds() * 1000L, modem);
        runtime.startThreads();
        runtime.installShutdownHook();
        return runtime;
    }

    private void startThreads() {
        consumers.forEach(EmissionConsumer::start);
        cancellationConsumer.start();
        startFlusher();
        startHeartbeat();
        LOG.info("agent runtime started: gateway={} operators={} workDir={}",
                gatewayId, consumers.stream().map(EmissionConsumer::topic).toList(),
                Path.of(spool.file().toString()).getParent());
    }

    /** Flushes the spool to Kafka in order, one iteration per second. */
    private void startFlusher() {
        if (!flusherRunning.compareAndSet(false, true)) {
            return;
        }
        flusher = new Thread(() -> {
            while (!closed.get() && !Thread.currentThread().isInterrupted()) {
                flushOnce();
                sleepQuietly(1000);
            }
        }, "hs-agent-spool-flush");
        flusher.setDaemon(true);
        flusher.start();
    }

    /** Drains the spool once; exposed for tests and for the local admin API (P7-S4). */
    public int flushOnce() {
        try {
            int sent = spool.flushTo(sender);
            if (sent > 0) {
                flushed.addAndGet(sent);
                LOG.info("flushed {} spooled record(s) to Kafka", sent);
            }
            return sent;
        } catch (RuntimeException e) {
            LOG.warn("spool flush iteration failed: {}", e.getMessage());
            return 0;
        }
    }

    private void startHeartbeat() {
        if (!heartbeatRunning.compareAndSet(false, true)) {
            return;
        }
        heartbeatThread = new Thread(() -> {
            while (!closed.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    heartbeat.pulse();
                } catch (RuntimeException e) {
                    LOG.warn("heartbeat failed: {}", e.getMessage());
                }
                sleepQuietly(heartbeatIntervalMs);
            }
        }, "hs-agent-heartbeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    private void installShutdownHook() {
        shutdownHook = new Thread(this::close, "hs-agent-shutdown");
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            LOG.debug("shutdown hook not installed (JVM already shutting down)");
        }
    }

    /** Number of records successfully published to Kafka since start. */
    public long flushedCount() {
        return flushed.get();
    }

    /** Internals exposed for the simulator and the admin API (P7-S4). */
    public List<EmissionConsumer> consumers() {
        return List.copyOf(consumers);
    }

    public CancellationConsumer cancellationConsumer() {
        return cancellationConsumer;
    }

    public HeartbeatProducer heartbeatProducer() {
        return heartbeat;
    }

    public Spool spool() {
        return spool;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        consumers.forEach(EmissionConsumer::close);
        cancellationConsumer.close();
        flushOnce();
        sender.close();
        try {
            modem.close();
        } catch (IOException | RuntimeException e) {
            LOG.warn("modem close failed: {}", e.getMessage());
        }
        LOG.info("agent runtime stopped; records flushed to Kafka: {}", flushed.get());
    }

    /** Blocks the caller until {@link #close()} runs (used by {@code AgentMain}). */
    public void awaitTermination() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Thread ping = new Thread(() -> {
            while (!closed.get()) {
                sleepQuietly(200);
            }
            latch.countDown();
        }, "hs-agent-await");
        ping.setDaemon(true);
        ping.start();
        latch.await();
    }

    private static void ensureDirectory(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create workDir " + dir, e);
        }
    }

    private static String serialOf(Modem modem) {
        String serial = modem.getDeviceInformation() == null
                ? null : modem.getDeviceInformation().getSerialNo();
        return serial == null || serial.isBlank() ? modem.portName() : serial;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Heartbeat data for a runtime with exactly one modem and no SIM registry yet (P3). */
    private static final class RuntimeHeartbeatSource implements HeartbeatSource {

        private final AgentConfig config;
        private final Path configFile;
        private final Modem modem;
        private final List<EmissionConsumer> consumers;
        private final Instant startedAt;
        private final String agentVersion;
        private final String policyHash;
        private final String configHash;

        RuntimeHeartbeatSource(AgentConfig config, Path configFile, Modem modem,
                               List<EmissionConsumer> consumers, Instant startedAt,
                               String agentVersion) {
            this.config = config;
            this.configFile = configFile;
            this.modem = modem;
            this.consumers = consumers;
            this.startedAt = startedAt;
            this.agentVersion = agentVersion;
            this.configHash = hashOfFile(configFile, config.toString());
            this.policyHash = hashOfFile(Path.of(config.workDir()).resolve(config.policy().path()), "");
        }

        @Override
        public String gatewayId() {
            return config.gatewayId();
        }

        @Override
        public String agentVersion() {
            return agentVersion;
        }

        @Override
        public String policyHash() {
            return policyHash;
        }

        @Override
        public String configHash() {
            return configHash;
        }

        @Override
        public Long uptimeSeconds() {
            return Duration.between(startedAt, Instant.now()).toSeconds();
        }

        @Override
        public List<GatewayHeartbeat.ModemInfo> modems() {
            var info = modem.getDeviceInformation();
            String status = switch (modem.state()) {
                case READY -> GatewayHeartbeat.ModemInfo.Status.READY;
                case BUSY -> GatewayHeartbeat.ModemInfo.Status.BUSY;
                case DEGRADED -> GatewayHeartbeat.ModemInfo.Status.DEGRADED;
                default -> GatewayHeartbeat.ModemInfo.Status.ERROR;
            };
            return List.of(new GatewayHeartbeat.ModemInfo(
                    serialOf(modem),
                    info == null ? null : info.getModel(),
                    info == null ? null : info.getManufacturer(),
                    status,
                    modem.portName(),
                    null,
                    null));
        }

        @Override
        public List<GatewayHeartbeat.SimInfo> sims() {
            // SIM identity (iccid/imsi hashes) arrives with the fleet registry (P3-S1);
            // this build never reads raw SIM identifiers.
            return List.of();
        }

        @Override
        public Map<String, Long> counters() {
            long delivered = consumers.stream().mapToLong(EmissionConsumer::deliveredCount).sum();
            long rejected = consumers.stream().mapToLong(EmissionConsumer::rejectedCount).sum();
            return Map.of(
                    "emissionsHandled", delivered,
                    "emissionsRejected", rejected);
        }

        private static String hashOfFile(Path file, String fallback) {
            if (file == null) {
                return Hashes.sha256(fallback);
            }
            try {
                return Hashes.sha256(Files.readAllBytes(file));
            } catch (IOException | RuntimeException e) {
                return Hashes.sha256(fallback);
            }
        }
    }
}
