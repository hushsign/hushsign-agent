package com.hushsign.agent.runtime.audit;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The agent's append-only audit log (P7-S2).
 *
 * <p>Recording must never take the agent down: a failed audit write is a serious problem, but
 * a silent SMS that is not logged is worse, so the contract is "record what we can and carry
 * on" and the failure is reported through {@link #status()} for the heartbeat and the admin
 * API to surface.
 */
public interface AuditLog {

    /** Appends one entry, or returns empty when the log is not writable. */
    Optional<AuditEntry> record(String type, String emissionId, String detail);

    /** Walks the chain and reports the first place it does not hold. */
    ChainVerification verify();

    /** The last {@code count} entries, oldest first. */
    List<AuditEntry> tail(int count);

    /** Where the log is, for an operator who has to hand it over. */
    String location();

    /** Whether the last write succeeded; false here is an incident, not a warning. */
    boolean status();

    /**
     * Summarises the entries written in {@code [from, to)} (P7-S3).
     *
     * <p>{@code chainHead} is the hash of the newest entry in the window and {@code chainPrev} is
     * the chain head as it stood when the window opened. The platform compares one digest's
     * {@code chainPrev} with the previous digest's {@code chainHead}: if the two differ, entries
     * were removed or rewritten in between, and nothing on the device can hide that.
     *
     * @return empty when nothing was written in the window, since a digest with no head cannot
     *         be expressed and an empty hour is not evidence of anything
     */
    Optional<DigestWindow> window(Instant from, Instant to);

    /**
     * The answer for one window.
     *
     * @param entryCount how many entries the window contains
     * @param chainHead  hash of the newest entry in the window
     * @param chainPrev  chain head when the window opened, or {@code null} for a fresh chain
     */
    record DigestWindow(long entryCount, String chainHead, String chainPrev) {
    }

    /**
     * The verdict of a verification run.
     *
     * @param entries    how many entries were read
     * @param brokenAt   the sequence number where the chain broke, or -1 when it holds
     * @param problem    what is wrong, or null when nothing is
     * @param verifiedAt when the walk was made
     */
    record ChainVerification(boolean ok, long entries, long brokenAt, String problem, Instant verifiedAt) {

        public static ChainVerification ok(long entries, Instant at) {
            return new ChainVerification(true, entries, -1, null, at);
        }

        public static ChainVerification broken(long entries, long brokenAt, String problem, Instant at) {
            return new ChainVerification(false, entries, brokenAt, problem, at);
        }
    }
}
