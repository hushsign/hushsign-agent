package com.hushsign.agent.runtime.admin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** P7-S4: the two flags, and the fact that a kill is not undone by a resume. */
class AdminStateTest {

    private final AdminState state = new AdminState();

    @Test
    void anAgentStartsRunning() {
        assertFalse(state.paused());
        assertFalse(state.killed());
    }

    @Test
    void pauseAndResumeReportWhetherTheyChangedAnything() {
        assertTrue(state.pause(), "the first pause is a change");
        assertFalse(state.pause(), "a second pause is not");
        assertTrue(state.paused());

        assertTrue(state.resume());
        assertFalse(state.resume(), "resuming a running agent is not a change");
        assertFalse(state.paused());
    }

    @Test
    void aKillIsNotUndoneByResume() {
        assertTrue(state.kill());

        state.resume();

        assertTrue(state.killed(), "only a restart clears the kill switch");
        assertFalse(state.kill(), "killing twice is not a change");
    }
}
