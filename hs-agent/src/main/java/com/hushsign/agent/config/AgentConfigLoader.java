package com.hushsign.agent.config;

import com.hushsign.agent.config.AgentConfig.DedupeConfig;
import com.hushsign.agent.config.AgentConfig.HeartbeatConfig;
import com.hushsign.agent.config.AgentConfig.KafkaConfig;
import com.hushsign.agent.config.AgentConfig.ModemsConfig;
import com.hushsign.agent.config.AgentConfig.PolicyConfig;
import com.hushsign.agent.config.AgentConfig.SecurityConfig;
import com.hushsign.agent.config.AgentConfig.SpoolConfig;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Loads {@code agent.yaml} (P1-S10) with strict validation and environment
 * overrides.
 *
 * <ul>
 *   <li>Missing file &#8594; defaults, unless required fields are absent.</li>
 *   <li>Unknown YAML keys are rejected (typos fail fast).</li>
 *   <li>Environment variables ({@code HS_GATEWAY_ID},
 *       {@code HS_KAFKA_BOOTSTRAP_SERVERS}, {@code HS_MODEMS_PORTS}, ...)
 *       override file values; lists are comma-separated, booleans are
 *       {@code true}/{@code false}.</li>
 *   <li>Secrets (SASL password) come from the environment, never the file.</li>
 * </ul>
 */
public final class AgentConfigLoader {

    private static final Pattern OPERATOR = Pattern.compile("^[0-9]{3}-[0-9]{2,3}$");

    private static final Set<String> ROOT_KEYS = Set.of("gatewayId", "operators", "workDir",
            "kafka", "modems", "heartbeat", "dedupe", "spool", "policy");
    private static final Set<String> KAFKA_KEYS = Set.of("bootstrapServers", "clientId", "security");
    private static final Set<String> SECURITY_KEYS = Set.of("protocol", "saslMechanism",
            "saslUsername", "saslPassword", "truststorePath");
    private static final Set<String> MODEMS_KEYS = Set.of("autoScan", "ports", "baudRate",
            "responseTimeoutMs", "commandWaitMs", "pollWaitMs", "probeAttempts", "maxConsecutiveErrors");
    private static final Set<String> HEARTBEAT_KEYS = Set.of("intervalSeconds");
    private static final Set<String> DEDUPE_KEYS = Set.of("maxEntries");
    private static final Set<String> SPOOL_KEYS = Set.of("path");
    private static final Set<String> POLICY_KEYS = Set.of("path");

    private AgentConfigLoader() {
    }

    /** Loads from the given file path and environment (env wins over the file). */
    public static AgentConfig load(Path file, Map<String, String> env) {
        Map<String, Object> root = parse(file);
        return build(root, env);
    }

    /** Loads with the process environment as override source. */
    public static AgentConfig load(Path file) {
        return load(file, System.getenv());
    }

    private static Map<String, Object> parse(Path file) {
        if (file == null || !Files.exists(file)) {
            return Map.of();
        }
        try {
            String yaml = Files.readString(file);
            Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
            if (parsed == null) {
                return Map.of();
            }
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new ConfigException(file + " must be a YAML mapping, got " + parsed.getClass().getSimpleName());
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) map;
            return cast;
        } catch (IOException e) {
            throw new ConfigException("cannot read " + file, e);
        }
    }

    private static AgentConfig build(Map<String, Object> root, Map<String, String> env) {
        checkKeys(root, ROOT_KEYS, "");

        String gatewayId = str(root, "", "gatewayId", env, null);
        if (gatewayId == null || gatewayId.isBlank() || gatewayId.length() > 128) {
            throw new ConfigException("gatewayId is required (file key gatewayId or env HS_GATEWAY_ID)");
        }
        List<String> operators = strList(root, "", "operators", env, List.of());
        if (operators.isEmpty()) {
            throw new ConfigException("operators is required (at least one MCC-MNC like 226-10)");
        }
        for (String operator : operators) {
            if (!OPERATOR.matcher(operator).matches()) {
                throw new ConfigException("operator must be MCC-MNC like 226-10, got " + operator);
            }
        }

        String workDir = str(root, "", "workDir", env, ".");

        Map<String, Object> kafka = section(root, "kafka");
        checkKeys(kafka, KAFKA_KEYS, "kafka.");
        Map<String, Object> security = section(kafka, "security");
        checkKeys(security, SECURITY_KEYS, "kafka.security.");
        KafkaConfig kafkaConfig = new KafkaConfig(
                str(kafka, "kafka.", "bootstrapServers", env, "localhost:9092"),
                str(kafka, "kafka.", "clientId", env, null),
                new SecurityConfig(
                        str(security, "kafka.security.", "protocol", env, "PLAINTEXT"),
                        str(security, "kafka.security.", "saslMechanism", env, null),
                        str(security, "kafka.security.", "saslUsername", env, null),
                        str(security, "kafka.security.", "saslPassword", env, null),
                        str(security, "kafka.security.", "truststorePath", env, null)));

        Map<String, Object> modems = section(root, "modems");
        checkKeys(modems, MODEMS_KEYS, "modems.");
        boolean autoScan = bool(modems, "modems.", "autoScan", env, true);
        List<String> ports = strList(modems, "modems.", "ports", env, List.of());
        if (!autoScan && ports.isEmpty()) {
            throw new ConfigException("modems.autoScan is false but modems.ports is empty");
        }
        ModemsConfig modemsConfig = new ModemsConfig(
                autoScan,
                ports,
                positive(intVal(modems, "modems.", "baudRate", env, 115_200), "modems.baudRate"),
                positive(intVal(modems, "modems.", "responseTimeoutMs", env, 30_000), "modems.responseTimeoutMs"),
                nonNegative(intVal(modems, "modems.", "commandWaitMs", env, 700), "modems.commandWaitMs"),
                positive(intVal(modems, "modems.", "pollWaitMs", env, 200), "modems.pollWaitMs"),
                positive(intVal(modems, "modems.", "probeAttempts", env, 3), "modems.probeAttempts"),
                positive(intVal(modems, "modems.", "maxConsecutiveErrors", env, 3), "modems.maxConsecutiveErrors"));

        Map<String, Object> heartbeat = section(root, "heartbeat");
        checkKeys(heartbeat, HEARTBEAT_KEYS, "heartbeat.");
        HeartbeatConfig heartbeatConfig = new HeartbeatConfig(
                positive(intVal(heartbeat, "heartbeat.", "intervalSeconds", env, 30), "heartbeat.intervalSeconds"));

        Map<String, Object> dedupe = section(root, "dedupe");
        checkKeys(dedupe, DEDUPE_KEYS, "dedupe.");
        DedupeConfig dedupeConfig = new DedupeConfig(
                positive(intVal(dedupe, "dedupe.", "maxEntries", env, 10_000), "dedupe.maxEntries"));

        Map<String, Object> spool = section(root, "spool");
        checkKeys(spool, SPOOL_KEYS, "spool.");
        SpoolConfig spoolConfig = new SpoolConfig(str(spool, "spool.", "path", env, "spool"));

        Map<String, Object> policy = section(root, "policy");
        checkKeys(policy, POLICY_KEYS, "policy.");
        PolicyConfig policyConfig = new PolicyConfig(str(policy, "policy.", "path", env, "policy.yaml"));

        return new AgentConfig(gatewayId, operators, workDir, kafkaConfig, modemsConfig,
                heartbeatConfig, dedupeConfig, spoolConfig, policyConfig);
    }

    // ==================================================
    // helpers
    // ==================================================

    private static Map<String, Object> section(Map<String, Object> parent, String name) {
        Object value = parent.get(name);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new ConfigException(name + " must be a mapping, got " + value.getClass().getSimpleName());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> cast = (Map<String, Object>) map;
        return cast;
    }

    private static void checkKeys(Map<String, Object> map, Set<String> allowed, String path) {
        for (String key : map.keySet()) {
            if (!allowed.contains(key)) {
                throw new ConfigException("unknown config key " + path + key
                        + " (allowed: " + allowed + ")");
            }
        }
    }

    private static String envKey(String path, String key) {
        // kafka.bootstrapServers -> HS_KAFKA_BOOTSTRAP_SERVERS, gatewayId -> HS_GATEWAY_ID
        String flattened = (path + key).replace('.', '_');
        String underscored = flattened.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        return "HS_" + underscored.toUpperCase(Locale.ROOT);
    }

    private static String str(Map<String, Object> map, String path, String key,
                              Map<String, String> env, String defaultValue) {
        String name = envKey(path, key);
        String envValue = env.get(name);
        if (envValue != null) {
            return envValue;
        }
        Object value = map.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static int intVal(Map<String, Object> map, String path, String key,
                              Map<String, String> env, int defaultValue) {
        String name = envKey(path, key);
        String envValue = env.get(name);
        if (envValue != null) {
            return parseInt(name, envValue);
        }
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return parseInt(path + key, String.valueOf(value));
    }

    private static boolean bool(Map<String, Object> map, String path, String key,
                                Map<String, String> env, boolean defaultValue) {
        String name = envKey(path, key);
        String envValue = env.get(name);
        if (envValue != null) {
            if ("true".equalsIgnoreCase(envValue)) {
                return true;
            }
            if ("false".equalsIgnoreCase(envValue)) {
                return false;
            }
            throw new ConfigException(name + " must be true or false, got " + envValue);
        }
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if ("true".equalsIgnoreCase(String.valueOf(value))) {
            return true;
        }
        if ("false".equalsIgnoreCase(String.valueOf(value))) {
            return false;
        }
        throw new ConfigException(path + key + " must be true or false, got " + value);
    }

    private static List<String> strList(Map<String, Object> map, String path, String key,
                                        Map<String, String> env, List<String> defaultValue) {
        String name = envKey(path, key);
        String envValue = env.get(name);
        if (envValue != null) {
            return split(envValue);
        }
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
        return split(String.valueOf(value));
    }

    private static List<String> split(String commaSeparated) {
        return java.util.Arrays.stream(commaSeparated.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static int parseInt(String name, String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException(name + " must be an integer, got '" + raw + "'");
        }
    }

    private static int positive(int value, String name) {
        if (value < 1) {
            throw new ConfigException(name + " must be >= 1, got " + value);
        }
        return value;
    }

    private static int nonNegative(int value, String name) {
        if (value < 0) {
            throw new ConfigException(name + " must be >= 0, got " + value);
        }
        return value;
    }
}
