package com.hushsign.agent.gsm.modem;

/** Modem operational state — mirrors the {@code status} enum of the wire schema. */
public enum ModemState {
    READY,
    BUSY,
    DEGRADED,
    ERROR
}
