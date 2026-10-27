package com.hushsign.agent.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hushsign.agent.runtime.policy.engine.PolicySettings;
import com.hushsign.agent.runtime.policy.engine.PolicySettingsLoader;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * P7-S7: the two files the distribution ships must actually load.
 *
 * <p>An example configuration that no longer parses is worse than none: it is the first thing an
 * installer copies, and the agent will refuse to start with a message about a key the operator
 * never wrote. Both are checked here on every build.
 */
class ExampleConfigsTest {

    private static final Path CONFIG = Path.of("src/main/resources/agent.example.yaml");
    private static final Path POLICY = Path.of("src/main/resources/policy.example.yaml");

    @Test
    void theExampleAgentConfigurationLoads() {
        AgentConfig config = AgentConfigLoader.load(CONFIG, Map.of());

        assertNotNull(config.gatewayId());
        assertTrue(config.operators().contains("226-10"));
        assertEquals("policy.yaml", config.policy().path(),
                "the shipped layout keeps policy.yaml next to agent.yaml");
    }

    @Test
    void theExamplePolicyLoads() {
        PolicySettings policy = PolicySettingsLoader.load(POLICY);

        assertFalse(policy.killSwitch(), "a shipped policy must not arrive already stopped");
        assertTrue(policy.allowedTechniques().contains("SILENT_TP0"));
        assertEquals(6, policy.caps().perTargetPerHour());
        assertNotNull(policy.quietHours(), "the example shows the quiet-hours block");
        assertEquals("Europe/Bucharest", policy.quietHours().zone().getId());
    }
}
