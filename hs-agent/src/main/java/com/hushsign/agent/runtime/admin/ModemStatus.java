package com.hushsign.agent.runtime.admin;

/**
 * One modem as the admin API reports it (P7-S4).
 *
 * <p>{@code imsi} is the masked form. The admin API answers over loopback with a bearer token,
 * which makes it the most exposed surface the agent has, so it never carries a raw subscriber
 * identity — the same rule the clean log follows.
 */
public record ModemStatus(
        String port,
        String state,
        String imsi,
        String manufacturer,
        String model,
        String serialNumber,
        int rssi) {
}
