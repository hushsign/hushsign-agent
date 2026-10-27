package com.hushsign.agent.runtime.admin;

import java.util.List;

/**
 * What the agent tells an operator who asks (P7-S4).
 *
 * <p>Deliberately a fixed record rather than a map: the admin API answers questions about a
 * device that is sending real messages, so the shape of the answer should not drift silently
 * when a field is added somewhere else.
 *
 * @param gatewayId        the gateway this agent speaks for
 * @param agentVersion     build version, or {@code unknown} when it was not stamped
 * @param uptimeSeconds    seconds since the runtime started
 * @param paused           the operator has paused sending; the agent is up and spooling
 * @param killed           the kill switch is engaged from the API; only a restart clears it
 * @param policyKillSwitch the kill switch as configured in {@code policy.yaml}
 * @param policyTechniques the techniques currently allowed, empty meaning any
 * @param policyPath       the policy file in force, so a reload can be pointed at
 * @param configPath       the config file in force, or {@code null} when it was not read from disk
 * @param policyHash       hash of the policy file as reported in the heartbeat
 * @param configHash       hash of the config file as reported in the heartbeat
 * @param spoolDepth       records waiting to go to Kafka
 * @param spoolBytes       bytes those records occupy
 * @param quarantined      records parked because Kafka refused them
 * @param flushed          records this process has delivered
 * @param auditOk          the audit chain verifies over everything on this device
 * @param auditHead        sequence number of the last audit entry
 * @param auditLocation    where the chain lives, for the operator who has to hand it over
 */
public record AdminStatus(
        String gatewayId,
        String agentVersion,
        long uptimeSeconds,
        boolean paused,
        boolean killed,
        boolean policyKillSwitch,
        List<String> policyTechniques,
        String policyPath,
        String configPath,
        String policyHash,
        String configHash,
        int spoolDepth,
        long spoolBytes,
        long quarantined,
        long flushed,
        boolean auditOk,
        long auditHead,
        String auditLocation) {
}
