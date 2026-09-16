package com.hushsign.agent.simulator;

/**
 * Scriptable simulator scenarios (plan P1-S5): the four behaviors the edge
 * runtime must survive. A scenario can be switched at runtime to replay
 * transitions (e.g. success &#8594; offline) without restarting the mock.
 */
public enum SimScenario {

    /** Every PDU is accepted; {@code +CMGS: n} indexes count up from 1. */
    SUCCESS,

    /** Every PDU is refused with {@code +CMS ERROR: 500} (generic failure). */
    FAILURE,

    /**
     * The radio/network is gone: the AT surface still answers (so the driver
     * passes the {@code AT+CMGS} prompt stage), but the final PDU response
     * never arrives and sends time out.
     */
    OFFLINE,

    /** The network refuses the ping: {@code +CMS ERROR: 304} (operation not allowed). */
    POLICY_REJECT
}
