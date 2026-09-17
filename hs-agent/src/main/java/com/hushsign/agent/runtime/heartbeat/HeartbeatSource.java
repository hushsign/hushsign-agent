package com.hushsign.agent.runtime.heartbeat;

import com.hushsign.protocol.v1.GatewayHeartbeat;

import java.util.List;
import java.util.Map;

/**
 * Supplies the gateway health data for one heartbeat (P1-S9). Implemented by
 * the runtime: identity, policy/config hashes, modem + SIM snapshots and
 * counters. Data minimization: SIM identifiers only as SHA-256 hashes,
 * MSISDN only masked.
 */
public interface HeartbeatSource {

    String gatewayId();

    /** Agent build version, max 32 chars. */
    String agentVersion();

    /** SHA-256 of the effective policy file (64 lowercase hex). */
    String policyHash();

    /** SHA-256 of the effective agent config (64 lowercase hex). */
    String configHash();

    /** Seconds since process start, or {@code null} when unknown. */
    Long uptimeSeconds();

    List<GatewayHeartbeat.ModemInfo> modems();

    List<GatewayHeartbeat.SimInfo> sims();

    /** Non-negative counters (transmissions, rejections, expiries, ...). */
    Map<String, Long> counters();
}
