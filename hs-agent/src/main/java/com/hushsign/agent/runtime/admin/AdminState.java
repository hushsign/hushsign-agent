package com.hushsign.agent.runtime.admin;

import com.hushsign.agent.runtime.policy.RuntimeState;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The two flags the operator can flip at runtime (P7-S4).
 *
 * <p>Both live in memory on purpose. A pause is "not right now" and should not survive a
 * restart, and a kill is "stop until someone looks at this" — persisting it would let a
 * forgotten kill switch keep a gateway silent for weeks, which is its own outage. The
 * deployment's standing answer is the kill switch in {@code policy.yaml}.
 */
public final class AdminState implements RuntimeState {

    private final AtomicBoolean paused = new AtomicBoolean();
    private final AtomicBoolean killed = new AtomicBoolean();

    @Override
    public boolean paused() {
        return this.paused.get();
    }

    @Override
    public boolean killed() {
        return this.killed.get();
    }

    /** Returns true when this call changed the state, so the caller only audits real changes. */
    public boolean pause() {
        return this.paused.compareAndSet(false, true);
    }

    public boolean resume() {
        return this.paused.compareAndSet(true, false);
    }

    /** One-way for the life of the process: there is deliberately no un-kill over HTTP. */
    public boolean kill() {
        return this.killed.compareAndSet(false, true);
    }
}
