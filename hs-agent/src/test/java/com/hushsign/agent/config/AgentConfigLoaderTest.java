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

    /** No environment overrides: a constant so a lambda only ever calls the loader. */
    private static final Map<String, String> NO_ENV = Map.of();

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

        assertEquals("4f2c9c1e-0f4e-4f36-9d43-2f0a4a6b1c11", config.gatewayId());
        assertEquals(List.of("226-10"), config.operators());
        assertEquals("localhost:9092", config.kafka().bootstrapServers());
        assertEquals("PLAINTEXT", config.kafka().security().protocol());
        assertTrue(config.modems().autoScan());
        assertEquals(115_200, config.modems().baudRate());
        assertEquals("FULL_HEX", config.modems().pduSubmission());
        assertEquals(30, config.heartbeat().intervalSeconds());
        assertEquals(10_000, config.dedupe().maxEntries());
        assertEquals("spool", config.spool().path());
        assertEquals("policy.yaml", config.policy().path());
    }

    @Test
    void gatewayIdAndOperatorsAreRequired() {
        Map<String, String> empty = Map.of();
        assertThrows(ConfigException.class, () -> AgentConfigLoader.load(null, empty),
                "gatewayId must be required");
        Map<String, String> onlyGateway = Map.of("HS_GATEWAY_ID", "4f2c9c1e-0f4e-4f36-9d43-2f0a4a6b1c11");
        assertThrows(ConfigException.class, () -> AgentConfigLoader.load(null, onlyGateway),
                "operators must be required");
    }

    @Test
    void aGatewayIdThatIsNotAUuidIsRefusedAtStartup() {
        // The env map is built outside the lambda so the only thing that can throw inside it is
        // the load - otherwise the assertion passes when Map.of throws instead.
        Map<String, String> env = Map.of("HS_GATEWAY_ID", "gw-buc-01", "HS_OPERATORS", "226-10");
        ConfigException refused = assertThrows(ConfigException.class,
                () -> AgentConfigLoader.load(null, env));

        assertTrue(refused.getMessage().contains("uuid"), refused.getMessage());
        assertTrue(refused.getMessage().contains("fleet registry"), "the operator needs to know where the id comes from");
    }

    @Test
    void aGatewayIdIsCanonicalised() {
        // An operator who pastes the id from the console in upper case gets the same identity,
        // not a second gateway that never matches the fleet row.
        AgentConfig config = AgentConfigLoader.load(null, Map.of(
                "HS_GATEWAY_ID", "4F2C9C1E-0F4E-4F36-9D43-2F0A4A6B1C11", "HS_OPERATORS", "226-10"));

        assertEquals("4f2c9c1e-0f4e-4f36-9d43-2f0a4a6b1c11", config.gatewayId());
    }

    @Test
    void defaultsApplyWhenTheFileIsMissing() {
        AgentConfig config = AgentConfigLoader.load(null, Map.of(
                "HS_GATEWAY_ID", "4f2c9c1e-0f4e-4f36-9d43-2f0a4a6b1c11", "HS_OPERATORS", "226-10"));

        assertEquals(".", config.workDir());
        assertEquals("localhost:9092", config.kafka().bootstrapServers());
        assertTrue(config.modems().autoScan());
        assertEquals(3, config.modems().probeAttempts());
        assertEquals(30, config.heartbeat().intervalSeconds());
    }

    @Test
    void environmentOverridesTheFile() throws Exception {
        Path file = write("""
                gatewayId: 9a1e5f2b-1111-4a2b-8c3d-000000000000
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
                "HS_GATEWAY_ID", "9a1e5f2b-1111-4a2b-8c3d-000000000001",
                "HS_HEARTBEAT_INTERVAL_SECONDS", "15",
                "HS_DEDUPE_MAX_ENTRIES", "5000",
                "HS_MODEMS_AUTO_SCAN", "false",
                "HS_MODEMS_PORTS", "/dev/ttyUSB0, /dev/ttyUSB1",
                "HS_KAFKA_BOOTSTRAP_SERVERS", "kafka.internal:9093"));

        assertEquals("9a1e5f2b-1111-4a2b-8c3d-000000000001", config.gatewayId());
        assertEquals(15, config.heartbeat().intervalSeconds());
        assertEquals(5000, config.dedupe().maxEntries());
        assertFalse(config.modems().autoScan());
        assertEquals(List.of("/dev/ttyUSB0", "/dev/ttyUSB1"), config.modems().ports());
        assertEquals("kafka.internal:9093", config.kafka().bootstrapServers());
    }

    @Test
    void saslPasswordComesFromTheEnvironment() throws Exception {
        Path file = write("""
                gatewayId: 4f2c9c1e-0f4e-4f36-9d43-2f0a4a6b1c11
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
                gatewayId: 4f2c9c1e-0f4e-4f36-9d43-2f0a4a6b1c11
                operators: ["226-10"]
                bogusKey: true
                """);
        assertThrows(ConfigException.class, () -> AgentConfigLoader.load(file, NO_ENV));

        Path nested = write("""
                gatewayId: 4f2c9c1e-0f4e-4f36-9d43-2f0a4a6b1c11
                operators: ["226-10"]
                modems:
                  baud: 9600
                """);
        assertThrows(ConfigException.class, () -> AgentConfigLoader.load(nested, NO_ENV));
    }

    @Test
    void invalidValuesAreRejected() {
        Map<String, String> badOperator = Map.of(
                "HS_GATEWAY_ID", "4f2c9c1e-0f4e-4f36-9d43-2f0a4a6b1c11", "HS_OPERATORS", "abc");
        assertThrows(ConfigException.class, () -> AgentConfigLoader.load(null, badOperator),
                "operators must match MCC-MNC");

        Map<String, String> zeroInterval = Map.of(
                "HS_GATEWAY_ID", "4f2c9c1e-0f4e-4f36-9d43-2f0a4a6b1c11", "HS_OPERATORS", "226-10",
                "HS_HEARTBEAT_INTERVAL_SECONDS", "0");
        assertThrows(ConfigException.class, () -> AgentConfigLoader.load(null, zeroInterval),
                "interval must be positive");

        Map<String, String> yesIsNotABoolean = Map.of(
                "HS_GATEWAY_ID", "4f2c9c1e-0f4e-4f36-9d43-2f0a4a6b1c11", "HS_OPERATORS", "226-10",
                "HS_MODEMS_AUTO_SCAN", "yes");
        assertThrows(ConfigException.class, () -> AgentConfigLoader.load(null, yesIsNotABoolean),
                "booleans must be true/false");

        Map<String, String> unparseableBaud = Map.of(
                "HS_GATEWAY_ID", "4f2c9c1e-0f4e-4f36-9d43-2f0a4a6b1c11", "HS_OPERATORS", "226-10",
                "HS_MODEMS_BAUD_RATE", "fast");
        assertThrows(ConfigException.class, () -> AgentConfigLoader.load(null, unparseableBaud),
                "ints must parse");
    }

    @Test
    void nonMappingSectionsAreRejected() throws Exception {
        Path file = write("""
                gatewayId: 4f2c9c1e-0f4e-4f36-9d43-2f0a4a6b1c11
                operators: ["226-10"]
                modems: 9600
                """);
        assertThrows(ConfigException.class, () -> AgentConfigLoader.load(file, NO_ENV));
    }

    @Test
    void autoScanFalseRequiresExplicitPorts() throws Exception {
        Path file = write("""
                gatewayId: 4f2c9c1e-0f4e-4f36-9d43-2f0a4a6b1c11
                operators: ["226-10"]
                modems:
                  autoScan: false
                  ports: []
                """);
        assertThrows(ConfigException.class, () -> AgentConfigLoader.load(file, NO_ENV));

        Path ok = write("""
                gatewayId: 4f2c9c1e-0f4e-4f36-9d43-2f0a4a6b1c11
                operators: ["226-10"]
                modems:
                  autoScan: false
                  ports: ["/dev/ttyUSB0"]
                """);
        AgentConfig config = AgentConfigLoader.load(ok, Map.of());
        assertFalse(config.modems().autoScan());
        assertEquals(List.of("/dev/ttyUSB0"), config.modems().ports());
    }

    @Test
    void pduSubmissionIsSelectableAndValidated() throws Exception {
        Path file = write("""
                gatewayId: 4f2c9c1e-0f4e-4f36-9d43-2f0a4a6b1c11
                operators: ["226-10"]
                modems:
                  autoScan: false
                  ports: ["COM6"]
                  pduSubmission: tpdu_hex
                """);

        AgentConfig config = AgentConfigLoader.load(file, Map.of());
        assertEquals("TPDU_HEX", config.modems().pduSubmission(),
                "the Huawei K3765 family needs the TPDU form (P1-S14)");

        Map<String, String> sharedEnv = Map.of(
                "HS_GATEWAY_ID", "4f2c9c1e-0f4e-4f36-9d43-2f0a4a6b1c11", "HS_OPERATORS", "226-10",
                "HS_MODEMS_AUTO_SCAN", "false", "HS_MODEMS_PORTS", "COM6",
                "HS_MODEMS_PDU_SUBMISSION", "binary");
        assertThrows(ConfigException.class, () -> AgentConfigLoader.load(null, sharedEnv),
                "an unknown pduSubmission must be refused, not defaulted");
    }

    @Test
    void simPinComesFromTheEnvironment() {
        AgentConfig config = AgentConfigLoader.load(null, Map.of(
                "HS_GATEWAY_ID", "4f2c9c1e-0f4e-4f36-9d43-2f0a4a6b1c11",
                "HS_OPERATORS", "226-10",
                "HS_MODEMS_SIM_PIN", "1234"));

        assertEquals("1234", config.modems().simPin());
    }
}
