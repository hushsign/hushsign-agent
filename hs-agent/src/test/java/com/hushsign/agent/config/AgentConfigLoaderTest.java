package com.hushsign.agent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-S10 tests: file loading, defaults, env overrides, strict key checks and
 * value validation.
 */
class AgentConfigLoaderTest {

    @TempDir
    Path tempDir;

    private Path write(String yaml) throws IOException {
        Path file = tempDir.resolve("agent.yaml");
        Files.writeString(file, yaml);
        return file;
    }

    @Test
    void loadsTheExampleConfiguration() throws Exception {
        Path file = tempDir.resolve("agent.yaml");
        Files.copy(Path.of("src/main/resources/agent.example.yaml"), file);

        AgentConfig config = AgentConfigLoader.load(file, Map.of());

        assertEquals("gw-buc-01", config.gatewayId());
        assertEquals(List.of("226-10"), config.operators());
        assertEquals("localhost:9092", config.kafka().bootstrapServers());
        assertEquals("PLAINTEXT", config.kafka().security().protocol());
        assertTrue(config.modems().autoScan());
        assertEquals(115_200, config.modems().baudRate());
        assertEquals(30, config.heartbeat().intervalSeconds());
        assertEquals(10_000, config.dedupe().maxEntries());
        assertEquals("spool", config.spool().path());
        assertEquals("policy.yaml", config.policy().path());
    }

    @Test
    void gatewayIdAndOperatorsAreRequired() {
        assertThrows(ConfigException.class, () -> AgentConfigLoader.load(null, Map.of()),
                "gatewayId must be required");
        assertThrows(ConfigException.class,
                () -> AgentConfigLoader.load(null, Map.of("HS_GATEWAY_ID", "gw-1")),
                "operators must be required");
    }

    @Test
    void defaultsApplyWhenTheFileIsMissing() {
        AgentConfig config = AgentConfigLoader.load(null, Map.of(
                "HS_GATEWAY_ID", "gw-1", "HS_OPERATORS", "226-10"));

        assertEquals(".", config.workDir());
        assertEquals("localhost:9092", config.kafka().bootstrapServers());
        assertTrue(config.modems().autoScan());
        assertEquals(3, config.modems().probeAttempts());
        assertEquals(30, config.heartbeat().intervalSeconds());
    }

    @Test
    void environmentOverridesTheFile() throws Exception {
        Path file = write("""
                gatewayId: gw-from-file
                operators: ["226-10"]
                heartbeat:
                  intervalSeconds: 30
                dedupe:
                  maxEntries: 100
                modems:
                  autoScan: true
                  ports: []
                """);

        AgentConfig config = AgentConfigLoader.load(file, Map.of(
                "HS_GATEWAY_ID", "gw-from-env",
                "HS_HEARTBEAT_INTERVAL_SECONDS", "15",
                "HS_DEDUPE_MAX_ENTRIES", "5000",
                "HS_MODEMS_AUTO_SCAN", "false",
                "HS_MODEMS_PORTS", "/dev/ttyUSB0, /dev/ttyUSB1",
                "HS_KAFKA_BOOTSTRAP_SERVERS", "kafka.internal:9093"));

        assertEquals("gw-from-env", config.gatewayId());
        assertEquals(15, config.heartbeat().intervalSeconds());
        assertEquals(5000, config.dedupe().maxEntries());
        assertFalse(config.modems().autoScan());
        assertEquals(List.of("/dev/ttyUSB0", "/dev/ttyUSB1"), config.modems().ports());
        assertEquals("kafka.internal:9093", config.kafka().bootstrapServers());
    }

    @Test
    void saslPasswordComesFromTheEnvironment() throws Exception {
        Path file = write("""
                gatewayId: gw-1
                operators: ["226-10"]
                kafka:
                  bootstrapServers: kafka:9092
                  security:
                    protocol: SASL_SSL
                    saslMechanism: SCRAM-SHA-512
                    saslUsername: gw-226-10
                """);

        AgentConfig config = AgentConfigLoader.load(file, Map.of(
                "HS_KAFKA_SECURITY_SASL_PASSWORD", "topsecret"));

        assertEquals("SASL_SSL", config.kafka().security().protocol());
        assertEquals("SCRAM-SHA-512", config.kafka().security().saslMechanism());
        assertEquals("gw-226-10", config.kafka().security().saslUsername());
        assertEquals("topsecret", config.kafka().security().saslPassword());
    }

    @Test
    void unknownKeysAreRejected() throws Exception {
        Path file = write("""
                gatewayId: gw-1
                operators: ["226-10"]
                bogusKey: true
                """);
        assertThrows(ConfigException.class, () -> AgentConfigLoader.load(file, Map.of()));

        Path nested = write("""
                gatewayId: gw-1
                operators: ["226-10"]
                modems:
                  baud: 9600
                """);
        assertThrows(ConfigException.class, () -> AgentConfigLoader.load(nested, Map.of()));
    }

    @Test
    void invalidValuesAreRejected() throws Exception {
        assertThrows(ConfigException.class,
                () -> AgentConfigLoader.load(null, Map.of("HS_GATEWAY_ID", "gw-1", "HS_OPERATORS", "abc")),
                "operators must match MCC-MNC");
        assertThrows(ConfigException.class,
                () -> AgentConfigLoader.load(null, Map.of(
                        "HS_GATEWAY_ID", "gw-1", "HS_OPERATORS", "226-10",
                        "HS_HEARTBEAT_INTERVAL_SECONDS", "0")),
                "interval must be positive");
        assertThrows(ConfigException.class,
                () -> AgentConfigLoader.load(null, Map.of(
                        "HS_GATEWAY_ID", "gw-1", "HS_OPERATORS", "226-10",
                        "HS_MODEMS_AUTO_SCAN", "yes")),
                "booleans must be true/false");
        assertThrows(ConfigException.class,
                () -> AgentConfigLoader.load(null, Map.of(
                        "HS_GATEWAY_ID", "gw-1", "HS_OPERATORS", "226-10",
                        "HS_MODEMS_BAUD_RATE", "fast")),
                "ints must parse");
    }

    @Test
    void nonMappingSectionsAreRejected() throws Exception {
        Path file = write("""
                gatewayId: gw-1
                operators: ["226-10"]
                modems: 9600
                """);
        assertThrows(ConfigException.class, () -> AgentConfigLoader.load(file, Map.of()));
    }

    @Test
    void autoScanFalseRequiresExplicitPorts() throws Exception {
        Path file = write("""
                gatewayId: gw-1
                operators: ["226-10"]
                modems:
                  autoScan: false
                  ports: []
                """);
        assertThrows(ConfigException.class, () -> AgentConfigLoader.load(file, Map.of()));

        Path ok = write("""
                gatewayId: gw-1
                operators: ["226-10"]
                modems:
                  autoScan: false
                  ports: ["/dev/ttyUSB0"]
                """);
        AgentConfig config = AgentConfigLoader.load(ok, Map.of());
        assertFalse(config.modems().autoScan());
        assertEquals(List.of("/dev/ttyUSB0"), config.modems().ports());
    }
}
