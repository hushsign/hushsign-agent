package com.hushsign.agent.runtime.policy.engine;

import com.hushsign.agent.config.ConfigException;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Loads {@code policy.yaml} (P7-S1). Same rules as the agent's own configuration: unknown
 * keys are refused (a typo in an allow list must not silently become "allow everything"),
 * and a missing file is an error rather than a default - an agent without a policy is an
 * agent that sends whatever it is told to.
 *
 * <pre>
 * killSwitch: false
 * techniques:
 *   allow: [SILENT_TP0, MWI_DEACTIVATE]
 * operators:
 *   allow: [226-10]
 * caps:
 *   perTargetPerHour: 6
 *   perOperatorPerHour: 120
 *   perGatewayPerHour: 600
 * quietHours:
 *   start: "23:00"
 *   end: "06:00"
 *   zone: Europe/Bucharest
 * commands:
 *   maxAgeSeconds: 300
 * </pre>
 */
public final class PolicySettingsLoader {

    /** The only key either list section accepts, and the one an operator most often misspells. */
    private static final String KEY_ALLOW = "allow";

    /** The one section that decides when it is too late at night to send. */
    private static final String KEY_QUIET_HOURS = "quietHours";

    private static final Set<String> ROOT_KEYS =
            Set.of("killSwitch", "techniques", "operators", "caps", KEY_QUIET_HOURS, "commands", "visibleSms");
    private static final Set<String> TECHNIQUE_KEYS = Set.of(KEY_ALLOW);
    private static final Set<String> OPERATOR_KEYS = Set.of(KEY_ALLOW);
    private static final Set<String> CAP_KEYS = Set.of("perTargetPerHour", "perOperatorPerHour", "perGatewayPerHour");
    private static final Set<String> QUIET_KEYS = Set.of("start", "end", "zone");
    private static final Set<String> COMMAND_KEYS = Set.of("maxAgeSeconds");

    private PolicySettingsLoader() {
    }

    /** Reads and validates a policy file. */
    public static PolicySettings load(Path file) {
        if (file == null) {
            throw new ConfigException("policy.path must name a policy file");
        }
        if (!Files.isRegularFile(file)) {
            throw new ConfigException("policy file not found: " + file.toAbsolutePath()
                    + " - refusing to run without a policy");
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
            return build(root == null ? Map.of() : root, file.toAbsolutePath().toString());
        } catch (IOException e) {
            throw new ConfigException("cannot read policy file " + file + ": " + e.getMessage(), e);
        }
    }

    static PolicySettings build(Map<String, Object> root, String where) {
        checkKeys(root, ROOT_KEYS, "", where);

        Map<String, Object> techniques = section(root, "techniques", where);
        checkKeys(techniques, TECHNIQUE_KEYS, "techniques.", where);
        Set<String> allowedTechniques = strings(techniques.get(KEY_ALLOW), "techniques.allow", where);

        Map<String, Object> operators = section(root, "operators", where);
        checkKeys(operators, OPERATOR_KEYS, "operators.", where);
        Set<String> allowedOperators = strings(operators.get(KEY_ALLOW), "operators.allow", where);

        Map<String, Object> caps = section(root, "caps", where);
        checkKeys(caps, CAP_KEYS, "caps.", where);
        PolicySettings.Caps capsValue = new PolicySettings.Caps(
                nonNegative(caps.get("perTargetPerHour"), "caps.perTargetPerHour", where, 0),
                nonNegative(caps.get("perOperatorPerHour"), "caps.perOperatorPerHour", where, 0),
                nonNegative(caps.get("perGatewayPerHour"), "caps.perGatewayPerHour", where, 0));

        PolicySettings.QuietHours quietHours = quietHours(root, where);
        Duration maxAge = maxCommandAge(root, where);

        return new PolicySettings(truthy(root.get("killSwitch")), allowedTechniques, allowedOperators, capsValue,
                quietHours, maxAge, truthy(root.get("visibleSms")));
    }

    private static PolicySettings.QuietHours quietHours(Map<String, Object> root, String where) {
        if (!root.containsKey(KEY_QUIET_HOURS)) {
            return null;
        }
        Map<String, Object> quiet = section(root, KEY_QUIET_HOURS, where);
        checkKeys(quiet, QUIET_KEYS, "quietHours.", where);
        LocalTime start = time(quiet.get("start"), "quietHours.start", where);
        LocalTime end = time(quiet.get("end"), "quietHours.end", where);
        Object zone = quiet.get("zone");
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(zone == null ? "UTC" : String.valueOf(zone).trim());
        } catch (java.time.DateTimeException _) {
            throw new ConfigException("quietHours.zone is not a zone id: " + zone + " (" + where + ")");
        }
        return new PolicySettings.QuietHours(start, end, zoneId);
    }

    private static Duration maxCommandAge(Map<String, Object> root, String where) {
        Map<String, Object> commands = section(root, "commands", where);
        checkKeys(commands, COMMAND_KEYS, "commands.", where);
        Object value = commands.get("maxAgeSeconds");
        if (value == null) {
            return null;
        }
        return Duration.ofSeconds(nonNegative(value, "commands.maxAgeSeconds", where, 0));
    }

    private static LocalTime time(Object value, String key, String where) {
        if (value == null) {
            throw new ConfigException(key + " is required (" + where + ")");
        }
        try {
            return LocalTime.parse(String.valueOf(value).trim());
        } catch (DateTimeParseException _) {
            throw new ConfigException(key + " must be HH:mm, got " + value + " (" + where + ")");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> parent, String key, String where) {
        Object value = parent.get(key);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map)) {
            throw new ConfigException(key + " must be a mapping (" + where + ")");
        }
        return (Map<String, Object>) value;
    }

    private static void checkKeys(Map<String, Object> map, Set<String> allowed, String prefix, String where) {
        for (String key : map.keySet()) {
            if (!allowed.contains(key)) {
                throw new ConfigException("unknown policy key " + prefix + key + " (" + where + ")");
            }
        }
    }

    private static Set<String> strings(Object value, String key, String where) {
        if (value == null) {
            return Set.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new ConfigException(key + " must be a list (" + where + ")");
        }
        Set<String> values = new LinkedHashSet<>();
        for (Object element : list) {
            if (element == null || String.valueOf(element).isBlank()) {
                throw new ConfigException(key + " has a blank entry (" + where + ")");
            }
            values.add(String.valueOf(element).trim());
        }
        return values;
    }

    private static int nonNegative(Object value, String key, String where, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number number)) {
            throw new ConfigException(key + " must be a number (" + where + ")");
        }
        int parsed = number.intValue();
        if (parsed < 0) {
            throw new ConfigException(key + " cannot be negative (" + where + ")");
        }
        return parsed;
    }

    private static boolean truthy(Object value) {
        return value instanceof Boolean flag ? flag : value != null && Boolean.parseBoolean(String.valueOf(value));
    }
}
