package com.hushsign.agent.runtime.spool;

/**
 * One spooled outbound Kafka record (P1-S8): topic, message key and the
 * serialized value JSON. Spooled for results, heartbeats and digests when
 * the broker is unreachable.
 */
public record SpooledRecord(String topic, String key, String value) {

    public SpooledRecord {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("spooled topic must not be blank");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("spooled value must not be blank");
        }
        // key is optional for some topics
    }
}
