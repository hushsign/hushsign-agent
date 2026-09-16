/**
 * hs-agent — the edge runtime: Kafka I/O, modem pool, execution loop, policy engine,
 * audit chain, dedupe, spool, heartbeat and local admin API/CLI.
 *
 * <p>The agent is outbound-only and fail-closed; the customer-owned policy file
 * (kill switch, limits, freshness gate) is authoritative.
 */
package com.hushsign.agent;
