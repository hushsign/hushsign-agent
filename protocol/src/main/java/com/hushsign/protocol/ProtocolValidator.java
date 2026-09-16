package com.hushsign.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Validates wire payloads against the v1 JSON Schemas bundled as
 * {@code protocol/schemas/v1/*.json}.
 *
 * <p>Payload rules from the architecture: camelCase JSON, unknown fields ignored
 * (schemas never set {@code additionalProperties: false}), enums append-only,
 * {@code protocolVersion} required on every message.
 */
public final class ProtocolValidator {

    private static final String SCHEMA_BASE = "/protocol/schemas/v1/";

    private final Map<MessageType, JsonSchema> schemas = new EnumMap<>(MessageType.class);

    public ProtocolValidator() {
        // Draft 2020-12 treats "format" as an annotation by default; the wire
        // protocol requires assertion (uuid, date-time, regexes must be checked).
        SchemaValidatorsConfig config = new SchemaValidatorsConfig();
        config.setFormatAssertionsEnabled(true);
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        for (MessageType type : MessageType.values()) {
            String resource = SCHEMA_BASE + type.schemaName();
            try (InputStream in = ProtocolValidator.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException("schema resource not on classpath: " + resource);
                }
                schemas.put(type, factory.getSchema(in, config));
            } catch (IOException e) {
                throw new IllegalStateException("cannot read schema " + resource, e);
            }
        }
    }

    /** Validates raw JSON against the schema of the given message type. */
    public void validate(MessageType type, String json) {
        validate(type, ProtocolJson.tree(json));
    }

    /** Validates a parsed JSON node against the schema of the given message type. */
    public void validate(MessageType type, JsonNode node) {
        Set<ValidationMessage> messages = schemas.get(type).validate(node);
        if (!messages.isEmpty()) {
            throw new ProtocolValidationException(type, messages);
        }
    }
}
