package com.hushsign.agent.runtime.audit;

import com.hushsign.agent.runtime.spool.Spool;
import com.hushsign.protocol.MessageType;
import com.hushsign.protocol.ProtocolJson;
import com.hushsign.protocol.ProtocolValidator;
import com.hushsign.protocol.Topics;
import com.hushsign.protocol.v1.AuditDigest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Publishes the hourly summary of the device audit chain to {@code hs.audit.digest.v1} (P7-S3).
 *
 * <p>The point is that the platform can prove the device's chain was not rewritten. The full
 * entries never leave the gateway; what leaves is "in the hour beginning T there were N entries,
 * the newest hashed to H, and the chain head when the hour opened was P". The next digest's P is
 * the previous digest's H, so a device that deletes or edits the entries in between publishes a
 * P that does not match, and the platform says so.
 *
 * <p>An hour with no entries publishes nothing: a digest with no head cannot be expressed, and an
 * empty hour is not evidence of anything. Skipping it does not break continuity, because
 * {@code chainPrev} is the chain head at the start of the window rather than a literal reference
 * to the previous digest — nothing was written, so it is unchanged.
 *
 * <p>Window boundaries come from the clock, not from process start, so a restart mid-hour does not
 * shift the grid. Two digests for the same hour can only happen if the process is restarted
 * within that hour after having already published, and they would carry identical content; the
 * platform keys on (gatewayId, periodEnd) and treats that as idempotent.
 */
public final class AuditDigestPublisher {

    /** One hour, because the plan says hourly and an hour is the smallest useful slice. */
    public static final Duration PERIOD = Duration.ofHours(1);

    private final AuditLog audit;
    private final Spool spool;
    private final String gatewayId;
    private final Clock clock;
    private final ProtocolValidator validator = new ProtocolValidator();

    private Instant publishedThrough;

    public AuditDigestPublisher(AuditLog audit, Spool spool, String gatewayId, Clock clock) {
        this.audit = audit;
        this.spool = spool;
        this.gatewayId = gatewayId;
        this.clock = clock;
        this.publishedThrough = truncate(clock.instant());
    }

    /** The hour a pulse would report, given the current time. */
    public static Instant periodEndFor(Instant now) {
        return truncate(now);
    }

    /**
     * Publishes every completed hour that has not been published yet.
     *
     * <p>Called on a short tick; it does nothing until the clock crosses an hour boundary.
     *
     * @return the digest that was spooled, or empty when no hour was due
     */
    public synchronized Optional<AuditDigest> pulse() {
        Instant due = truncate(this.clock.instant());
        if (!due.isAfter(this.publishedThrough)) {
            return Optional.empty();
        }
        Instant from = this.publishedThrough;
        this.publishedThrough = due;

        Optional<AuditLog.DigestWindow> window = this.audit.window(from, due);
        if (window.isEmpty()) {
            return Optional.empty();
        }
        AuditLog.DigestWindow summary = window.get();
        AuditDigest digest = new AuditDigest(
                "1",
                this.gatewayId,
                from,
                due,
                summary.entryCount(),
                summary.chainHead(),
                summary.chainPrev() == null ? AuditEntry.GENESIS_HASH : summary.chainPrev());

        String json = ProtocolJson.write(digest);
        this.validator.validate(MessageType.AUDIT_DIGEST, json); // never spool garbage
        this.spool.enqueue(Topics.AUDIT_DIGEST, this.gatewayId, json);

        // Recorded after the window was read, so the digest's own entry belongs to the next hour.
        // That makes "the device said N entries hashing to H" something the chain itself attests.
        this.audit.append("AUDIT_DIGEST", null,
                "period " + from + ".." + due + " entries=" + summary.entryCount()
                        + " head=" + summary.chainHead());
        return Optional.of(digest);
    }

    /** The end of the last hour this publisher has accounted for. */
    public synchronized Instant publishedThrough() {
        return this.publishedThrough;
    }

    private static Instant truncate(Instant instant) {
        return instant.truncatedTo(ChronoUnit.HOURS);
    }
}
