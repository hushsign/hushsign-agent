package com.hushsign.agent.runtime.policy;

/**
 * What the operator has done to the agent since it started (P7-S4).
 *
 * <p>Separate from {@code PolicySettings}, which is the file on disk: the kill switch in that
 * file is the deployment's standing answer, while these two flags are somebody acting right
 * now. Both are checked, and the file's switch wins the ordering only because it is the one that
 * survives a restart.
 */
public interface RuntimeState {

    /** Nothing is sent while paused; the agent stays up and keeps spooling. */
    boolean paused();

    /** Engaged from the admin API: refuse everything until the agent is restarted. */
    boolean killed();

    /** The state of an agent that has never been told to do anything. */
    RuntimeState RUNNING = new RuntimeState() {
        @Override
        public boolean paused() {
            return false;
        }

        @Override
        public boolean killed() {
            return false;
        }
    };
}
