package com.hushsign.protocol;

import tools.jackson.databind.JsonNode;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Validates wire payloads against the v1 JSON Schemas bundled as
 * {@code protocol/schemas/v1/*.json}.
 *
 * <p>Payload rules from the architecture: camelCase JSON, unknown fields ignored
 * (schemas never set {@code additionalProperties: false}), enums append-only,
 * {@code protocolVersion} required on every message.
 *
 * <p>networknt 3.x replaced the old {@code JsonSchemaFactory}/{@code SpecVersion}/
 * {@code ValidationMessage} API with a {@link SchemaRegistry} that holds one dialect, and
 * {@link Schema#validate} now returns a {@code List<Error>}. The decisions are meant to be
 * identical to 1.5.6: the schema files did not change, and the tests that pin them did not
 * either - which is the point of pinning them.
 */
public final class ProtocolValidator {

    private static final String SCHEMA_BASE = "/protocol/schemas/v1/";

    private final Map<MessageType, Schema> schemas = new EnumMap<>(MessageType.class);

    public ProtocolValidator() {
        // Draft 2020-12 treats "format" as an annotation by default; the wire
        // protocol requires assertion (uuid, date-time, regexes must be checked).
        SchemaRegistryConfig config = SchemaRegistryConfig.builder()
                .formatAssertionsEnabled(true)
                .build();
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_2020_12, builder -> builder.schemaRegistryConfig(config));
        for (MessageType type : MessageType.values()) {
            String resource = SCHEMA_BASE + type.schemaName();
            try (InputStream in = ProtocolValidator.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException("schema resource not on classpath: " + resource);
                }
                schemas.put(type, registry.getSchema(in));
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
        List<Error> errors = schemas.get(type).validate(node);
        if (!errors.isEmpty()) {
            throw new ProtocolValidationException(type, errors);
        }
    }
}
