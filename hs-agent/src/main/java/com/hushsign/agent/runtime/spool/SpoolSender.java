package com.hushsign.agent.runtime.spool;

/**
 * Publishes one spooled record (P1-S8). Implementations wrap the Kafka
 * producer; throwing any exception marks the broker unreachable and stops
 * the flush — the failed record and everything after it stays spooled.
 */
@FunctionalInterface
public interface SpoolSender {

    void send(SpooledRecord record) throws Exception;
}
