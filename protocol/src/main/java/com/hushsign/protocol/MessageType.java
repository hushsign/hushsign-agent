package com.hushsign.protocol;

/**
 * The v1 wire message catalog. Each type maps to its JSON Schema
 * ({@code protocol/schemas/v1/}) and, where fixed, its Kafka topic.
 */
public enum MessageType {

    /** Platform → agent command; topic is per-operator: {@code hs.emissions.{operator}}. */
    EMISSION_COMMAND("emission-command.json", null),

    /** Platform → agent best-effort cancellation command. */
    EMISSION_CANCELLED("emission-cancelled.json", Topics.EMISSIONS_CANCELLED),

    /** Agent → platform transmission outcome. */
    TRANSMISSION_RESULT("transmission-result.json", Topics.TRANSMISSIONS),

    /** Agent → platform optional delivery report. */
    DELIVERY_REPORT("delivery-report.json", Topics.REPORTS_DELIVERY),

    /** Agent → platform health signal. */
    GATEWAY_HEARTBEAT("gateway-heartbeat.json", Topics.FLEET_HEARTBEAT),

    /** Agent → platform audit chain summary. */
    AUDIT_DIGEST("audit-digest.json", Topics.AUDIT_DIGEST);

    private final String schemaName;
    private final String topic;

    MessageType(String schemaName, String topic) {
        this.schemaName = schemaName;
        this.topic = topic;
    }

    public String schemaName() {
        return schemaName;
    }

    /** Fixed topic for this type, or {@code null} when the topic is dynamic (per-operator). */
    public String topic() {
        return topic;
    }

    /** Command types carry {@code issuedAt} and are subject to the freshness gate. */
    public boolean isCommand() {
        return this == EMISSION_COMMAND || this == EMISSION_CANCELLED;
    }
}
