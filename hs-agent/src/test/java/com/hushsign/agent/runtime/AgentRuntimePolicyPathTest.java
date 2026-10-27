package com.hushsign.agent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hushsign.agent.config.AgentConfig;
import com.hushsign.agent.config.AgentConfigLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P7-S7: where a relative {@code policy.path} is looked for.
 *
 * <p>An installed gateway keeps {@code agent.yaml} and {@code policy.yaml} together in
 * {@code /etc/hs-agent} while the work directory is {@code /var/lib/hs-agent}. Resolving only
 * against the work directory made the first real deployment refuse to start.
 */
class AgentRuntimePolicyPathTest {

    private static final Path EXAMPLE = Path.of("src/main/resources/agent.example.yaml");

    @TempDir
    Path tempDir;

    @Test
    void aPolicyBesideTheConfigFileWins() throws IOException {
        Path etc = Files.createDirectories(this.tempDir.resolve("etc"));
        Path configFile = Files.writeString(etc.resolve("agent.yaml"), Files.readString(EXAMPLE));
        Files.writeString(etc.resolve("policy.yaml"), "killSwitch: false\n");
        Path workDir = Files.createDirectories(this.tempDir.resolve("state"));

        Path resolved = AgentRuntime.resolvePolicyFile(config(), configFile, workDir);

        assertEquals(etc.resolve("policy.yaml"), resolved);
    }

    @Test
    void theWorkDirectoryIsTheFallback() throws IOException {
        Path etc = Files.createDirectories(this.tempDir.resolve("etc"));
        Path configFile = Files.writeString(etc.resolve("agent.yaml"), Files.readString(EXAMPLE));
        Path workDir = Files.createDirectories(this.tempDir.resolve("state"));
        Files.writeString(workDir.resolve("policy.yaml"), "killSwitch: false\n");

        Path resolved = AgentRuntime.resolvePolicyFile(config(), configFile, workDir);

        assertEquals(workDir.resolve("policy.yaml"), resolved);
    }

    @Test
    void anAbsolutePathIsTakenAsWritten() throws IOException {
        Path elsewhere = this.tempDir.resolve("elsewhere").resolve("rules.yaml");
        AgentConfig config = configWithPolicyPath(elsewhere.toString());

        Path resolved = AgentRuntime.resolvePolicyFile(config, null, this.tempDir);

        assertEquals(elsewhere.normalize(), resolved);
    }

    @Test
    void whenNeitherExistsTheErrorNamesThePlaceAnOperatorWouldLook() throws IOException {
        Path etc = Files.createDirectories(this.tempDir.resolve("etc"));
        Path configFile = etc.resolve("agent.yaml");
        Path workDir = Files.createDirectories(this.tempDir.resolve("state"));

        Path resolved = AgentRuntime.resolvePolicyFile(config(), configFile, workDir);

        assertEquals(etc.resolve("policy.yaml"), resolved,
                "the message that follows must point at /etc/hs-agent, not at the state directory");
    }

    private static AgentConfig config() throws IOException {
        return AgentConfigLoader.load(EXAMPLE, Map.of());
    }

    private static AgentConfig configWithPolicyPath(String path) throws IOException {
        AgentConfig base = config();
        return new AgentConfig(base.gatewayId(), base.operators(), base.workDir(), base.kafka(),
                base.modems(), base.heartbeat(), base.dedupe(), base.spool(),
                new AgentConfig.PolicyConfig(path));
    }
}
