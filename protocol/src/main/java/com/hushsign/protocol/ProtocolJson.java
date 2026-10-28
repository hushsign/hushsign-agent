package com.hushsign.protocol;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared Jackson configuration for the wire protocol: ISO-8601 instants,
 * unknown fields tolerated on read.
 *
 * <p>Jackson 3 configures a mapper through its builder rather than by mutating it, bundles
 * java.time support (no module to register), and made its exceptions unchecked - so a parse
 * failure is translated here rather than declared.
 */
public final class ProtocolJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private ProtocolJson() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("cannot parse " + type.getSimpleName() + " from JSON: " + e.getOriginalMessage(), e);
        }
    }

    public static JsonNode tree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("cannot parse JSON: " + e.getOriginalMessage(), e);
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("cannot serialize " + value.getClass().getSimpleName() + " to JSON: " + e.getOriginalMessage(), e);
        }
    }
}
