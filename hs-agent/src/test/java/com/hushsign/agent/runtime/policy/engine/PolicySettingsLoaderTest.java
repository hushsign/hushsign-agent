package com.hushsign.agent.runtime.policy.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hushsign.agent.config.ConfigException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P7-S1: the policy file is the deployment's own safety net, so loading it has to be
 * strict - a typo must fail the start, not quietly widen what the agent may send.
 */
class PolicySettingsLoaderTest {

    private static Path write(Path dir, String content) throws Exception {
        Path file = dir.resolve("policy.yaml");
        Files.writeString(file, content);
        return file;
    }

    @Test
    void readsTheWholeFile(@TempDir Path dir) throws Exception {
        PolicySettings settings = PolicySettingsLoader.load(write(dir, """
                killSwitch: false
                techniques:
                  allow: [SILENT_TP0, MWI_DEACTIVATE]
                operators:
                  allow: [226-10]
                caps:
                  perTargetPerHour: 6
                  perOperatorPerHour: 120
                  perGatewayPerHour: 600
                quietHours:
                  start: "23:00"
                  end: "06:00"
                  zone: Europe/Bucharest
                commands:
                  maxAgeSeconds: 300
                """));

        assertFalse(settings.killSwitch());
        assertEquals(Set.of("SILENT_TP0", "MWI_DEACTIVATE"), settings.allowedTechniques());
        assertEquals(Set.of("226-10"), settings.allowedOperators());
        assertEquals(6, settings.caps().perTargetPerHour());
        assertEquals(120, settings.caps().perOperatorPerHour());
        assertEquals(600, settings.caps().perGatewayPerHour());
        assertEquals(LocalTime.of(23, 0), settings.quietHours().start());
        assertEquals(LocalTime.of(6, 0), settings.quietHours().end());
        assertEquals(ZoneId.of("Europe/Bucharest"), settings.quietHours().zone());
        assertEquals(Duration.ofSeconds(300), settings.maxCommandAge());
    }

    @Test
    void anEmptyFileMeansNoAllowListAndNoCaps(@TempDir Path dir) throws Exception {
        PolicySettings settings = PolicySettingsLoader.load(write(dir, ""));

        assertTrue(settings.allowedTechniques().isEmpty());
        assertTrue(settings.allowedOperators().isEmpty());
        assertEquals(PolicySettings.Caps.UNLIMITED, settings.caps());
        assertNull(settings.quietHours());
        assertNull(settings.maxCommandAge());
    }

    @Test
    void aMissingFileIsRefused(@TempDir Path dir) {
        Path missing = dir.resolve("policy.yaml");
        ConfigException error = assertThrows(ConfigException.class,
                () -> PolicySettingsLoader.load(missing));

        assertTrue(error.getMessage().contains("refusing to run without a policy"), error.getMessage());
    }

    @Test
    void anUnknownKeyIsRefused(@TempDir Path dir) throws Exception {
        Path file = write(dir, "allowEverything: true");
        ConfigException error = assertThrows(ConfigException.class,
                () -> PolicySettingsLoader.load(file));

        assertTrue(error.getMessage().contains("unknown policy key allowEverything"), error.getMessage());
    }

    @Test
    void aTypoInsideASectionIsRefused(@TempDir Path dir) throws Exception {
        Path file = write(dir, """
                techniques:
                  allowed: [SILENT_TP0]
                """);
        ConfigException error = assertThrows(ConfigException.class,
                () -> PolicySettingsLoader.load(file));

        assertTrue(error.getMessage().contains("techniques.allowed"), error.getMessage());
    }

    @Test
    void aBadQuietHourIsRefused(@TempDir Path dir) throws Exception {
        Path file = write(dir, """
                quietHours:
                  start: "25:99"
                  end: "06:00"
                """);
        ConfigException error = assertThrows(ConfigException.class,
                () -> PolicySettingsLoader.load(file));

        assertTrue(error.getMessage().contains("quietHours.start must be HH:mm"), error.getMessage());
    }

    @Test
    void aNegativeCapIsRefused(@TempDir Path dir) throws Exception {
        Path file = write(dir, """
                caps:
                  perTargetPerHour: -1
                """);
        assertThrows(ConfigException.class, () -> PolicySettingsLoader.load(file));
    }

    @Test
    void theKillSwitchCanBeTurnedOn(@TempDir Path dir) throws Exception {
        assertTrue(PolicySettingsLoader.load(write(dir, "killSwitch: true")).killSwitch());
    }
}
