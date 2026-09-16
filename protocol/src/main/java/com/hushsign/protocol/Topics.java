package com.hushsign.protocol;

import java.util.regex.Pattern;

/**
 * Kafka topic names for the edge boundary (the only place Kafka is used).
 * Topic layout and semantics are defined in HushSign-Plan.md §4.4.
 */
public final class Topics {

    public static final String EMISSIONS_PREFIX = "hs.emissions.";
    public static final String EMISSIONS_CANCELLED = "hs.emissions.cancelled.v1";
    public static final String TRANSMISSIONS = "hs.transmissions.v1";
    public static final String REPORTS_DELIVERY = "hs.reports.delivery.v1";
    public static final String FLEET_HEARTBEAT = "hs.fleet.heartbeat.v1";
    public static final String AUDIT_DIGEST = "hs.audit.digest.v1";

    /** Consumer group prefix used by gateways of one operator: {@code gw-226-10}. */
    public static final String GATEWAY_GROUP_PREFIX = "gw-";

    /** Consumer group prefix used by platform modules: {@code cg-operations}. */
    public static final String MODULE_GROUP_PREFIX = "cg-";

    private static final Pattern OPERATOR = Pattern.compile("^[0-9]{3}-[0-9]{2,3}$");

    private Topics() {
    }

    /** {@code hs.emissions.226-10} for an MCC-MNC such as {@code 226-10}. */
    public static String emissions(String operator) {
        if (operator == null || !OPERATOR.matcher(operator).matches()) {
            throw new IllegalArgumentException("operator must be MCC-MNC like 226-10, got: " + operator);
        }
        return EMISSIONS_PREFIX + operator;
    }

    /** {@code gw-226-10}: the competing consumer group of gateways serving one operator. */
    public static String gatewayGroup(String operator) {
        if (operator == null || !OPERATOR.matcher(operator).matches()) {
            throw new IllegalArgumentException("operator must be MCC-MNC like 226-10, got: " + operator);
        }
        return GATEWAY_GROUP_PREFIX + operator;
    }
}
