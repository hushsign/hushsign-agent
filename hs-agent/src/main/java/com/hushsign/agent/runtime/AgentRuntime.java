package com.hushsign.agent.runtime;

import com.hushsign.agent.config.AgentConfig;
import com.hushsign.agent.gsm.modem.Modem;
import com.hushsign.agent.runtime.admin.AdminRuntime;
import com.hushsign.agent.runtime.admin.AdminState;
import com.hushsign.agent.runtime.admin.AdminStatus;
import com.hushsign.agent.runtime.admin.ModemStatus;
import com.hushsign.agent.runtime.sensitive.Sensitive;
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
import com.hushsign.agent.runtime.audit.AuditDigestPublisher;
import com.hushsign.agent.runtime.audit.AuditLog;
import com.hushsign.agent.runtime.audit.FileAuditChain;
import com.hushsign.agent.runtime.policy.engine.HourlyCounter;
import com.hushsign.agent.runtime.policy.engine.PolicyEngine;
import com.hushsign.agent.runtime.policy.engine.PolicySettings;
import com.hushsign.agent.runtime.policy.engine.PolicySettingsLoader;
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
public final class AgentRuntime implements AutoCloseable, AdminRuntime {

    private static final Logger LOG = LoggerFactory.getLogger(AgentRuntime.class);

    private final String gatewayId;
    private final Spool spool;
    private final KafkaSpoolSender sender;
    private final List<EmissionConsumer> consumers;
    private final CancellationConsumer cancellationConsumer;
    private final HeartbeatProducer heartbeat;
    private final long heartbeatIntervalMs;
    private final Modem modem;
    private final String agentVersion;
    private final PolicyEngine policy;
    private final Path policyFile;
    private final Path configFile;
    private final FileAuditChain audit;
    private final AdminState admin;
    private final HeartbeatSource heartbeatSource;
    private final AuditDigestPublisher digests;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong flushed = new AtomicLong();
    private final Instant startedAt = Instant.now();
    private final AtomicBoolean flusherRunning = new AtomicBoolean();
    private final AtomicBoolean heartbeatRunning = new AtomicBoolean();
    private Thread flusher;
    private Thread heartbeatThread;
    private Thread digestThread;
    private Thread shutdownHook;

    private AgentRuntime(String gatewayId, Spool spool, KafkaSpoolSender sender,
                         List<EmissionConsumer> consumers, CancellationConsumer cancellationConsumer,
                         HeartbeatProducer heartbeat, HeartbeatSource heartbeatSource,
                         long heartbeatIntervalMs, Modem modem, String agentVersion,
                         PolicyEngine policy, Path policyFile, Path configFile, FileAuditChain audit,
                         AdminState admin, AuditDigestPublisher digests) {
        this.gatewayId = gatewayId;
        this.spool = spool;
        this.sender = sender;
        this.consumers = consumers;
        this.cancellationConsumer = cancellationConsumer;
        this.heartbeat = heartbeat;
        this.heartbeatSource = heartbeatSource;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.modem = modem;
        this.agentVersion = agentVersion;
        this.policy = policy;
        this.policyFile = policyFile;
        this.configFile = configFile;
        this.audit = audit;
        this.admin = admin;
        this.digests = digests;
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

        Spool spool = Spool.open(workDir.resolve(config.spool().path()).normalize(),
                config.spool().maxBytes());
        DedupeStore dedupe = DedupeStore.open(
                workDir.resolve("dedupe.ids"), config.dedupe().maxEntries());
        CancellationStore cancels = CancellationStore.open(
                workDir.resolve("cancellations.ids"), config.dedupe().maxEntries());

        KafkaSpoolSender sender = new KafkaSpoolSender(config.kafka());
        TransmissionProducer transmissions = new TransmissionProducer(
                config.gatewayId(), serialOf(modem), null, spool);

        Clock clock = Clock.systemUTC();
        // P7-S1: the policy is loaded from disk before any consumer starts, and a missing or
        // invalid file stops the agent. An agent that cannot say what it is allowed to send is
        // an agent that must not send.
        Path policyFile = resolvePolicyFile(config, configFile, workDir);
        PolicySettings policySettings = PolicySettingsLoader.load(policyFile);
        // P7-S4: pause and kill are runtime flags the admin API flips; the engine consults them
        // before the file's own kill switch, so an operator acting now outranks the config.
        AdminState adminState = new AdminState();
        PolicyEngine policy = new PolicyEngine(policySettings, config.gatewayId(),
                new HourlyCounter(clock, workDir.resolve("policy-counters.journal")), clock, adminState);
        LOG.info("policy loaded from {}: killSwitch={} techniques={} operators={} caps={}/{}/{} quietHours={}",
                policySettings, policySettings.killSwitch(), policySettings.allowedTechniques(),
                policySettings.allowedOperators(), policySettings.caps().perTargetPerHour(),
                policySettings.caps().perOperatorPerHour(), policySettings.caps().perGatewayPerHour(),
                policySettings.quietHours());

        // P7-S2: the audit chain lives next to the spool and is verified by the same code the
        // offline CLI uses, so what the device claims and what the tool says cannot drift.
        FileAuditChain audit = FileAuditChain.open(workDir.resolve("audit"), config.gatewayId(), 0, clock);
        audit.append("STARTUP", null, "agent version " + agentVersion + ", operators " + config.operators());

        EmissionHandler chain = new DedupingEmissionHandler(dedupe,
                new CancellingEmissionHandler(cancels,
                        new PolicyCheckingEmissionHandler(policy,
                                new EmissionExecutor(modem::sendPdu, transmissions),
                                transmissions, audit, clock),
                        transmissions, clock));

        List<EmissionConsumer> consumers = new ArrayList<>();
        for (String operator : config.operators()) {
            consumers.add(new EmissionConsumer(config.kafka(), operator, chain));
        }
        CancellationConsumer cancellationConsumer =
                new CancellationConsumer(config.kafka(), config.gatewayId(), cancels);

        HeartbeatSource source = new RuntimeHeartbeatSource(config, configFile, policy, policyFile, modem, consumers,
                Instant.now(), agentVersion);
        HeartbeatProducer heartbeat = new HeartbeatProducer(source, spool);

        AgentRuntime runtime = new AgentRuntime(config.gatewayId(), spool, sender, consumers,
                cancellationConsumer, heartbeat, source, config.heartbeat().intervalSeconds() * 1000L,
                modem, agentVersion, policy, policyFile, configFile, audit, adminState,
                new AuditDigestPublisher(audit, spool, config.gatewayId(), clock));
        runtime.startThreads();
        runtime.installShutdownHook();
        return runtime;
    }

    private void startThreads() {
        consumers.forEach(EmissionConsumer::start);
        cancellationConsumer.start();
        startFlusher();
        startHeartbeat();
        startDigests();
        LOG.info("agent runtime started: gateway={} operators={} workDir={}",
                gatewayId, consumers.stream().map(EmissionConsumer::topic).toList(),
                Path.of(spool.file().toString()).getParent());
    }

    /**
     * P7-S3: a minute-by-minute tick that does nothing until the clock crosses an hour. The tick
     * is short so a digest goes out within a minute of the hour rather than at the next restart.
     */
    private void startDigests() {
        digestThread = new Thread(() -> {
            while (!closed.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    digests.pulse().ifPresent(digest -> LOG.info(
                            "audit digest published for {}..{}: {} entries",
                            digest.periodStart(), digest.periodEnd(), digest.entryCount()));
                } catch (RuntimeException e) {
                    LOG.warn("audit digest failed: {}", e.getMessage());
                }
                sleepQuietly(60_000);
            }
        }, "hs-agent-audit-digest");
        digestThread.setDaemon(true);
        digestThread.start();
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

    /** The audit chain, so the admin surface records into the same one the CLI verifies. */
    public AuditLog auditLog() {
        return audit;
    }


    @Override
    public AdminStatus status() {
        AuditLog.ChainVerification verification = audit.verify();
        PolicySettings settings = policy.settings();
        return new AdminStatus(
                gatewayId,
                agentVersion,
                Duration.between(startedAt, Instant.now()).toSeconds(),
                admin.paused(),
                admin.killed(),
                settings.killSwitch(),
                List.copyOf(settings.allowedTechniques()),
                policyFile == null ? null : policyFile.toString(),
                configFile == null ? null : configFile.toString(),
                heartbeatSource.policyHash(),
                heartbeatSource.configHash(),
                spool.size(),
                spool.sizeBytes(),
                spool.quarantined(),
                flushed.get(),
                verification.ok(),
                verification.entries(),
                audit.location());
    }

    @Override
    public List<ModemStatus> modems() {
        var info = modem.getDeviceInformation();
        return List.of(new ModemStatus(
                modem.portName(),
                modem.state().name(),
                info == null ? "" : Sensitive.of(info.getImsi()).masked(),
                info == null ? null : info.getManufacturer(),
                info == null ? null : info.getModel(),
                info == null ? null : info.getSerialNo(),
                info == null ? 0 : info.getRssi()));
    }

    /**
     * Re-reads the policy file. A file that does not parse leaves the rules already in force
     * untouched — an operator who saves a typo must not silently disable the policy — and the
     * hourly counters are deliberately kept, because a reload is not a way to reset a cap.
     */
    @Override
    public PolicyReload reloadPolicy() {
        if (policyFile == null) {
            return PolicyReload.refused("no policy file to reload");
        }
        try {
            PolicySettings reloaded = PolicySettingsLoader.load(policyFile);
            policy.applyPolicy(reloaded);
            LOG.warn("policy reloaded from {}: killSwitch={} techniques={} operators={}",
                    policyFile, reloaded.killSwitch(), reloaded.allowedTechniques(),
                    reloaded.allowedOperators());
            return PolicyReload.accepted("killSwitch=" + reloaded.killSwitch()
                    + " techniques=" + reloaded.allowedTechniques()
                    + " operators=" + reloaded.allowedOperators());
        } catch (RuntimeException e) {
            LOG.warn("policy reload refused, previous rules still in force: {}", e.getMessage());
            return PolicyReload.refused(String.valueOf(e.getMessage()));
        }
    }

    @Override
    public boolean pause() {
        return admin.pause();
    }

    @Override
    public boolean resume() {
        return admin.resume();
    }

    @Override
    public boolean kill() {
        return admin.kill();
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

    /**
     * Finds the policy file (P7-S7).
     *
     * <p>The installed layout keeps {@code agent.yaml} and {@code policy.yaml} together in
     * {@code /etc/hs-agent} while the work directory is {@code /var/lib/hs-agent}, so a relative
     * {@code policy.path} has to be looked for next to the configuration first. Resolving it only
     * against the work directory made every installed gateway fail to start with "policy file not
     * found", which is precisely what the first deployment on a real host did.
     *
     * <p>A developer run keeps both in the work directory, which is why that is the fallback.
     */
    static Path resolvePolicyFile(AgentConfig config, Path configFile, Path workDir) {
        Path configured = Path.of(config.policy().path());
        if (configured.isAbsolute()) {
            return configured.normalize();
        }
        Path besideConfig = null;
        if (configFile != null && configFile.toAbsolutePath().getParent() != null) {
            besideConfig = configFile.toAbsolutePath().getParent().resolve(configured).normalize();
            if (Files.isRegularFile(besideConfig)) {
                return besideConfig;
            }
        }
        Path underWorkDir = workDir.resolve(configured).normalize();
        if (Files.isRegularFile(underWorkDir) || besideConfig == null) {
            return underWorkDir;
        }
        // Neither exists: name the place an operator is most likely to have meant.
        return besideConfig;
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
        private final PolicyEngine policyEngine;
        private final Modem modem;
        private final List<EmissionConsumer> consumers;
        private final Instant startedAt;
        private final String agentVersion;
        private final String policyHash;
        private final String configHash;

        RuntimeHeartbeatSource(AgentConfig config, Path configFile, PolicyEngine policyEngine, Path policyFile,
                               Modem modem, List<EmissionConsumer> consumers, Instant startedAt,
                               String agentVersion) {
            this.config = config;
            this.policyEngine = policyEngine;
            this.modem = modem;
            this.consumers = consumers;
            this.startedAt = startedAt;
            this.agentVersion = agentVersion;
            this.configHash = hashOfFile(configFile, config.toString());
            // The hash must cover the file the agent actually enforces, not a path guess.
            this.policyHash = hashOfFile(policyFile, "");
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

        /**
         * The rules in force (P7-S13), read from the engine rather than from the file that was
         * loaded at start: a reload or a remote change must show up in the next heartbeat, not
         * after a restart.
         */
        @Override
        public GatewayHeartbeat.PolicyInfo policy() {
            PolicySettings inForce = policyEngine.settings();
            PolicySettings.QuietHours quiet = inForce.quietHours();
            return new GatewayHeartbeat.PolicyInfo(
                    inForce.killSwitch(),
                    new GatewayHeartbeat.PolicyInfo.Caps(
                            inForce.caps().perTargetPerHour(),
                            inForce.caps().perOperatorPerHour(),
                            inForce.caps().perGatewayPerHour()),
                    List.copyOf(inForce.allowedTechniques()),
                    List.copyOf(inForce.allowedOperators()),
                    quiet == null ? null
                            : new GatewayHeartbeat.PolicyInfo.QuietHours(
                                    quiet.start().toString(), quiet.end().toString(), quiet.zone().getId()),
                    inForce.maxCommandAge() == null ? null : inForce.maxCommandAge().toSeconds(),
                    GatewayHeartbeat.PolicyInfo.Source.LOCAL,
                    List.of());
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
