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
import java.util.UUID;
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

    private static final Pattern OPERATOR = Pattern.compile("^\\d{3}-\\d{2,3}$");

    // The section prefixes. Each appears once per key, inside the message an operator reads
    // when a key is wrong, so one constant per section keeps the eleven `modems.` messages
    // from drifting apart.
    private static final String KAFKA_PREFIX = "kafka.";
    private static final String SECURITY_PREFIX = "kafka.security.";
    private static final String MODEMS_PREFIX = "modems.";
    private static final String SPOOL_PREFIX = "spool.";
    /** Where the spool lives unless the file says otherwise. */
    private static final String DEFAULT_SPOOL_DIR = "spool";

    private static final Set<String> ROOT_KEYS = Set.of("gatewayId", "operators", "workDir",
            "kafka", "modems", "heartbeat", "dedupe", DEFAULT_SPOOL_DIR, "policy");
    private static final Set<String> KAFKA_KEYS = Set.of("bootstrapServers", "clientId", "security");
    private static final Set<String> SECURITY_KEYS = Set.of("protocol", "saslMechanism",
            "saslUsername", "saslPassword", "truststorePath", "truststorePassword");
    private static final Set<String> MODEMS_KEYS = Set.of("autoScan", "ports", "baudRate",
            "responseTimeoutMs", "commandWaitMs", "pollWaitMs", "probeAttempts",
            "maxConsecutiveErrors", "simPin", "pduSubmission");
    private static final Set<String> HEARTBEAT_KEYS = Set.of("intervalSeconds");
    private static final Set<String> DEDUPE_KEYS = Set.of("maxEntries");
    private static final Set<String> SPOOL_KEYS = Set.of("path", "maxBytes");
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
        if (gatewayId == null || gatewayId.isBlank()) {
            throw new ConfigException("gatewayId is required (file key gatewayId or env HS_GATEWAY_ID)");
        }
        gatewayId = canonicalGatewayId(gatewayId);
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
        checkKeys(kafka, KAFKA_KEYS, KAFKA_PREFIX);
        Map<String, Object> security = section(kafka, "security");
        checkKeys(security, SECURITY_KEYS, SECURITY_PREFIX);
        KafkaConfig kafkaConfig = new KafkaConfig(
                str(kafka, KAFKA_PREFIX, "bootstrapServers", env, "localhost:9092"),
                str(kafka, KAFKA_PREFIX, "clientId", env, null),
                new SecurityConfig(
                        str(security, SECURITY_PREFIX, "protocol", env, "PLAINTEXT"),
                        str(security, SECURITY_PREFIX, "saslMechanism", env, null),
                        str(security, SECURITY_PREFIX, "saslUsername", env, null),
                        str(security, SECURITY_PREFIX, "saslPassword", env, null),
                        str(security, SECURITY_PREFIX, "truststorePath", env, null),
                        str(security, SECURITY_PREFIX, "truststorePassword", env, null)));

        Map<String, Object> modems = section(root, "modems");
        checkKeys(modems, MODEMS_KEYS, MODEMS_PREFIX);
        boolean autoScan = bool(modems, MODEMS_PREFIX, "autoScan", env, true);
        List<String> ports = strList(modems, MODEMS_PREFIX, "ports", env, List.of());
        if (!autoScan && ports.isEmpty()) {
            throw new ConfigException("modems.autoScan is false but modems.ports is empty");
        }
        ModemsConfig modemsConfig = new ModemsConfig(
                autoScan,
                ports,
                positive(intVal(modems, MODEMS_PREFIX, "baudRate", env, 115_200), "modems.baudRate"),
                positive(intVal(modems, MODEMS_PREFIX, "responseTimeoutMs", env, 30_000), "modems.responseTimeoutMs"),
                nonNegative(intVal(modems, MODEMS_PREFIX, "commandWaitMs", env, 700), "modems.commandWaitMs"),
                positive(intVal(modems, MODEMS_PREFIX, "pollWaitMs", env, 200), "modems.pollWaitMs"),
                positive(intVal(modems, MODEMS_PREFIX, "probeAttempts", env, 3), "modems.probeAttempts"),
                positive(intVal(modems, MODEMS_PREFIX, "maxConsecutiveErrors", env, 3), "modems.maxConsecutiveErrors"),
                str(modems, MODEMS_PREFIX, "simPin", env, null),
                pduSubmission(modems, env));

        Map<String, Object> heartbeat = section(root, "heartbeat");
        checkKeys(heartbeat, HEARTBEAT_KEYS, "heartbeat.");
        HeartbeatConfig heartbeatConfig = new HeartbeatConfig(
                positive(intVal(heartbeat, "heartbeat.", "intervalSeconds", env, 30), "heartbeat.intervalSeconds"));

        Map<String, Object> dedupe = section(root, "dedupe");
        checkKeys(dedupe, DEDUPE_KEYS, "dedupe.");
        DedupeConfig dedupeConfig = new DedupeConfig(
                positive(intVal(dedupe, "dedupe.", "maxEntries", env, 10_000), "dedupe.maxEntries"));

        Map<String, Object> spool = section(root, DEFAULT_SPOOL_DIR);
        checkKeys(spool, SPOOL_KEYS, SPOOL_PREFIX);
        long spoolMaxBytes = longVal(spool, SPOOL_PREFIX, "maxBytes", env, 64L * 1024 * 1024);
        if (spoolMaxBytes < 1) {
            throw new ConfigException("spool.maxBytes must be >= 1, got " + spoolMaxBytes);
        }
        SpoolConfig spoolConfig = new SpoolConfig(
                str(spool, SPOOL_PREFIX, "path", env, DEFAULT_SPOOL_DIR), spoolMaxBytes);

        Map<String, Object> policy = section(root, "policy");
        checkKeys(policy, POLICY_KEYS, "policy.");
        PolicyConfig policyConfig = new PolicyConfig(str(policy, "policy.", "path", env, "policy.yaml"));

        return new AgentConfig(gatewayId, operators, workDir, kafkaConfig, modemsConfig,
                heartbeatConfig, dedupeConfig, spoolConfig, policyConfig);
    }

    // ==================================================
    // helpers
    // ==================================================

    /**
     * The gateway id must be the uuid the fleet registry assigned.
     *
     * <p>Every platform consumer - heartbeat, transmission result, audit digest - parses this
     * field as a {@code UUID} and drops what it cannot parse, logging a warning nobody reads.
     * A friendly name here therefore does not fail: it produces a gateway that starts, sends and
     * looks healthy while the platform discards all of it. Failing at startup is the only
     * outcome that does not waste an operator's afternoon.
     */
    private static String canonicalGatewayId(String value) {
        try {
            return UUID.fromString(value.trim()).toString();
        } catch (IllegalArgumentException _) {
            throw new ConfigException("gatewayId must be the uuid the fleet registry assigned "
                    + "(for example 4f2c9c1e-0f4e-4f36-9d43-2f0a4a6b1c11), got '" + value.trim() + "'");
        }
    }

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
        return (int) longVal(map, path, key, env, defaultValue);
    }

    private static long longVal(Map<String, Object> map, String path, String key,
                                Map<String, String> env, long defaultValue) {
        String name = envKey(path, key);
        String envValue = env.get(name);
        if (envValue != null) {
            return parseLong(name, envValue);
        }
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return parseLong(path + key, String.valueOf(value));
    }

    private static long parseLong(String name, String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException _) {
            throw new ConfigException(name + " must be an integer, got '" + raw + "'");
        }
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

    /**
     * {@code modems.pduSubmission} (P1-S14): {@code FULL_HEX} (standard) or
     * {@code TPDU_HEX} (Huawei K3765 family, which rejects the full form with
     * {@code +CMS ERROR: 304}). Defaults to the standard form.
     */
    private static String pduSubmission(Map<String, Object> modems, Map<String, String> env) {
        String value = str(modems, MODEMS_PREFIX, "pduSubmission", env, "FULL_HEX");
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!ModemsConfig.PDU_SUBMISSIONS.contains(normalized)) {
            throw new ConfigException("modems.pduSubmission must be one of "
                    + ModemsConfig.PDU_SUBMISSIONS + ", got " + value);
        }
        return normalized;
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
