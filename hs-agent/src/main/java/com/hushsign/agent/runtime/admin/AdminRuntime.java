package com.hushsign.agent.runtime.admin;

import java.util.List;

/**
 * The slice of the running agent that the admin surface is allowed to see and to change
 * (P7-S4).
 *
 * <p>An interface rather than the {@code AgentRuntime} class itself so the HTTP layer can be
 * tested without a Kafka cluster, a spool and a modem: the tests run the real server against a
 * fake that records what was asked of it.
 */
public interface AdminRuntime {

    AdminStatus status();

    List<ModemStatus> modems();

    /** Re-reads {@code policy.yaml}; a file that does not parse leaves the old rules in force. */
    PolicyReload reloadPolicy();

    /** Stops sending without stopping the agent. The boolean says whether anything changed. */
    boolean pause();

    boolean resume();

    /** Engages the kill switch until the process is restarted. */
    boolean kill();

    /** The outcome of a reload attempt, and the reason when it was refused. */
    record PolicyReload(boolean applied, String detail) {

        public static PolicyReload accepted(String detail) {
            return new PolicyReload(true, detail);
        }

        public static PolicyReload refused(String detail) {
            return new PolicyReload(false, detail);
        }
    }
}
