package com.hushsign.agent.simulator;

import com.hushsign.agent.config.AgentConfig;
import com.hushsign.agent.gsm.modem.Modem;
import com.hushsign.agent.gsm.modem.ModemConfig;
import com.hushsign.agent.runtime.AgentRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * P1-S13 E2E runner: the full agent runtime against a mock modem and a real
 * Kafka broker.
 *
 * <p>Chain (built by {@link AgentRuntime}, not here):
 * {@code EmissionConsumer → dedupe → cancellation → policy (accept-all stub)
 * → executor → SimModem}, with every transmission spooled to disk and flushed
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

    private SimulatedAgentApp() {
    }

    public static void main(String[] args) throws Exception {
        SimScenario scenario = args.length > 0 ? SimScenario.valueOf(args[0].toUpperCase()) : SimScenario.SUCCESS;
        String operator = args.length > 1 ? args[1] : "226-10";
        String bootstrap = System.getenv().getOrDefault("HS_KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        Path workDir = Path.of("target", "sim-agent").toAbsolutePath();
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

        AgentRuntime runtime = AgentRuntime.start(config(operator, bootstrap, workDir), null, modem, "simulated");
        LOG.info("simulated agent ready - awaiting emissions on hs.emissions.{}", operator);
        try {
            runtime.awaitTermination();
        } finally {
            runtime.close();
        }
    }

    /** Runtime wiring for the simulator: one mock modem, one operator, disk spool. */
    static AgentConfig config(String operator, String bootstrap, Path workDir) {
        return new AgentConfig(
                GATEWAY_ID,
                List.of(operator),
                workDir.toString(),
                new AgentConfig.KafkaConfig(bootstrap, GATEWAY_ID,
                        new AgentConfig.SecurityConfig("PLAINTEXT", null, null, null, null)),
                new AgentConfig.ModemsConfig(false, List.of(), 115_200, 1_500, 50, 50, 2, 3,
                        null, "FULL_HEX"),
                new AgentConfig.HeartbeatConfig(30),
                new AgentConfig.DedupeConfig(10_000),
                new AgentConfig.SpoolConfig("spool.jsonl"),
                new AgentConfig.PolicyConfig("policy.yaml"));
    }
}
