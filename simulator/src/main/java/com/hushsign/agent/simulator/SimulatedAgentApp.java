package com.hushsign.agent.simulator;

import com.hushsign.agent.gsm.modem.Modem;
import com.hushsign.agent.gsm.modem.ModemConfig;
import com.hushsign.agent.runtime.dedupe.DedupeStore;
import com.hushsign.agent.runtime.execution.EmissionExecutor;
import com.hushsign.agent.runtime.kafka.DedupingEmissionHandler;
import com.hushsign.agent.runtime.kafka.EmissionConsumer;
import com.hushsign.agent.runtime.kafka.EmissionHandler;
import com.hushsign.agent.runtime.kafka.KafkaSpoolSender;
import com.hushsign.agent.runtime.kafka.PolicyCheckingEmissionHandler;
import com.hushsign.agent.runtime.policy.AcceptAllPolicyProvider;
import com.hushsign.agent.runtime.spool.Spool;
import com.hushsign.agent.runtime.transmission.TransmissionProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P1-S13 E2E runner: the full agent pipeline against a mock modem and a real
 * Kafka broker.
 *
 * <p>Chain: {@code EmissionConsumer → dedupe → policy (accept-all stub) →
 * executor → SimModem}, with every transmission spooled to disk and flushed
 * to {@code hs.transmissions.v1} in order.
 *
 * <p>Usage: {@code SimulatedAgentApp [SUCCESS|FAILURE|OFFLINE|POLICY_REJECT]
 * [operator]}; broker from {@code HS_KAFKA_BOOTSTRAP_SERVERS} (default
 * {@code localhost:9092}). Stop with Ctrl-C.
 */
public final class SimulatedAgentApp {

    private static final Logger LOG = LoggerFactory.getLogger(SimulatedAgentApp.class);

    private static final String GATEWAY_ID = "sim-gw-01";
    private static final String SIM_ID = "SIM0";

    private static final AtomicLong SENT = new AtomicLong();

    private SimulatedAgentApp() {
    }

    public static void main(String[] args) throws Exception {
        SimScenario scenario = args.length > 0 ? SimScenario.valueOf(args[0].toUpperCase()) : SimScenario.SUCCESS;
        String operator = args.length > 1 ? args[1] : "226-10";
        String bootstrap = System.getenv().getOrDefault("HS_KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        Path workDir = Path.of("target", "sim-agent").toAbsolutePath();
        Files.createDirectories(workDir);
        LOG.info("workdir={} (user.dir={})", workDir, System.getProperty("user.dir"));

        LOG.info("starting simulated agent: gateway={} operator={} scenario={} broker={}",
                GATEWAY_ID, operator, scenario, bootstrap);

        SimModem sim = new SimModem(SIM_ID, scenario);
        ModemConfig modemConfig = ModemConfig.builder(GATEWAY_ID)
                .responseTimeoutMs(1500)
                .commandWaitMs(50)
                .pollWaitMs(50)
                .probeAttempts(2)
                .probeRetryDelayMs(100)
                .maxConsecutiveErrors(3)
                .build();
        Modem modem = Modem.open(sim, modemConfig);
        LOG.info("modem open (port={})", sim.name());

        Spool spool = Spool.open(workDir.resolve("spool.jsonl"));
        DedupeStore dedupe = DedupeStore.open(workDir.resolve("dedupe.txt"), 10_000);
        TransmissionProducer transmissions =
                new TransmissionProducer(GATEWAY_ID, SIM_ID, SIM_ID, spool);
        EmissionExecutor executor = new EmissionExecutor(modem::sendPdu, transmissions);
        EmissionHandler handler = new DedupingEmissionHandler(dedupe,
                new PolicyCheckingEmissionHandler(new AcceptAllPolicyProvider(), executor));
        EmissionConsumer consumer = new EmissionConsumer(bootstrap, operator, handler);
        KafkaSpoolSender sender = new KafkaSpoolSender(bootstrap);

        consumer.start();
        Thread flusher = startFlusher(spool, sender);
        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            consumer.close();
            flusher.interrupt();
            sender.close();
            try {
                modem.close();
            } catch (Exception e) {
                LOG.warn("modem close failed: {}", e.getMessage());
            }
            LOG.info("simulated agent stopped; records flushed to Kafka: {}", SENT.get());
            stop.countDown();
        }, "hs-sim-shutdown"));

        LOG.info("simulated agent ready - awaiting emissions on hs.emissions.{}", operator);
        stop.await();
    }

    private static Thread startFlusher(Spool spool, KafkaSpoolSender sender) {
        Thread flusher = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    int sent = spool.flushTo(sender);
                    if (sent > 0) {
                        SENT.addAndGet(sent);
                        LOG.info("flushed {} spooled record(s) to Kafka", sent);
                    }
                } catch (RuntimeException e) {
                    LOG.warn("spool flush iteration failed: {}", e.getMessage());
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "hs-sim-spool-flush");
        flusher.setDaemon(true);
        flusher.start();
        return flusher;
    }
}
