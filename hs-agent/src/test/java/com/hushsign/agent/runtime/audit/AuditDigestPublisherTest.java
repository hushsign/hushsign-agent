package com.hushsign.agent.runtime.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hushsign.agent.runtime.spool.Spool;
import com.hushsign.agent.runtime.spool.SpooledRecord;
import com.hushsign.protocol.ProtocolJson;
import com.hushsign.protocol.ProtocolValidator;
import com.hushsign.protocol.MessageType;
import com.hushsign.protocol.Topics;
import com.hushsign.protocol.v1.AuditDigest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P7-S3: the hourly digest, and the property it exists for — a chain that was rewritten between
 * two digests cannot claim continuity.
 *
 * <p>The tamper case is the point of the story, so the tests do it for real: they delete a line
 * out of the JSONL on disk and check that the platform-facing values stop lining up.
 */
class AuditDigestPublisherTest {

    private static final Instant NINE = Instant.parse("2026-10-27T09:05:00Z");
    private static final String GATEWAY = "gw-1";

    @TempDir
    Path workDir;

    private MutableClock clock;
    private FileAuditChain audit;
    private Spool spool;
    private AuditDigestPublisher publisher;

    @BeforeEach
    void open() {
        this.clock = new MutableClock(NINE);
        this.audit = FileAuditChain.open(this.workDir.resolve("audit"), GATEWAY, 0, this.clock);
        this.spool = Spool.open(this.workDir.resolve("spool.jsonl"));
        this.publisher = new AuditDigestPublisher(this.audit, this.spool, GATEWAY, this.clock);
    }

    @Test
    void nothingIsPublishedBeforeTheHourCloses() {
        recordEntries(3);

        assertTrue(this.publisher.pulse().isEmpty());
        assertEquals(0, this.spool.size());
        assertEquals(0, this.spool.peekAll().stream()
                .filter(record -> Topics.AUDIT_DIGEST.equals(record.topic())).count());
    }

    @Test
    void anHourWithEntriesProducesOneDigest() {
        recordEntries(4);
        String headOfTheHour = this.audit.tail(1).get(0).hash();

        Optional<AuditDigest> published = publishAt("2026-10-27T10:00:00Z");

        assertTrue(published.isPresent());
        AuditDigest digest = published.get();
        assertEquals("1", digest.protocolVersion());
        assertEquals(GATEWAY, digest.gatewayId());
        assertEquals(Instant.parse("2026-10-27T09:00:00Z"), digest.periodStart());
        assertEquals(Instant.parse("2026-10-27T10:00:00Z"), digest.periodEnd());
        assertEquals(4, digest.entryCount());
        assertEquals(AuditEntry.GENESIS_HASH, digest.chainPrev(), "the chain was empty when the hour opened");
        assertEquals(headOfTheHour, digest.chainHead());
    }

    @Test
    void theDigestIsSpooledOnTheDigestTopicUnderTheGateway() {
        recordEntries(1);

        publishAt("2026-10-27T10:00:00Z");

        List<SpooledRecord> digests = this.spool.peekAll().stream()
                .filter(record -> Topics.AUDIT_DIGEST.equals(record.topic())).toList();
        assertEquals(1, digests.size());
        assertEquals(GATEWAY, digests.get(0).key());
        new ProtocolValidator().validate(MessageType.AUDIT_DIGEST, digests.get(0).value());
        assertEquals(GATEWAY, ProtocolJson.read(digests.get(0).value(), AuditDigest.class).gatewayId());
    }

    @Test
    void theNextDigestContinuesFromThePreviousHead() {
        recordEntries(2);
        AuditDigest first = publishAt("2026-10-27T10:00:00Z").orElseThrow();

        recordEntries(3);
        AuditDigest second = publishAt("2026-10-27T11:00:00Z").orElseThrow();

        assertEquals(first.chainHead(), second.chainPrev(),
                "this equality is the whole continuity check the platform performs");
        assertEquals(4, second.entryCount(),
                "three sends plus the entry the previous digest wrote about itself");
        assertNotEquals(first.chainHead(), second.chainHead());
    }

    @Test
    void anHourWithNothingInItPublishesNothing() {
        // A chain that has never been written to: the first hour of a device that started and
        // then did nothing. A digest with no head cannot be expressed, so nothing goes out.
        assertTrue(publishAt("2026-10-27T10:00:00Z").isEmpty());
        assertEquals(0, this.spool.size());
    }

    @Test
    void aWindowWithNoWriteKeepsTheChainExactlyWhereItWas() {
        recordEntries(2);
        AuditDigest first = publishAt("2026-10-27T10:00:00Z").orElseThrow();

        // The only entry in the next hour is the one the first digest wrote about itself, so the
        // digest keeps coming and the chain never appears to stall.
        AuditDigest second = publishAt("2026-10-27T11:00:00Z").orElseThrow();

        assertEquals(first.chainHead(), second.chainPrev());
        assertEquals(1, second.entryCount());
    }

    @Test
    void removingAnEntryFromAClosedHourBreaksContinuity() throws IOException {
        recordEntries(3);
        AuditDigest first = publishAt("2026-10-27T10:00:00Z").orElseThrow();

        // Somebody edits the evidence: the last entry of the closed hour - the one whose hash
        // the digest published as its head - disappears from disk.
        Path chain = this.workDir.resolve("audit").resolve("audit-0001.jsonl");
        List<String> lines = Files.readAllLines(chain, StandardCharsets.UTF_8);
        Files.write(chain, lines.subList(0, lines.size() - 2), StandardCharsets.UTF_8);

        recordEntries(1);
        AuditDigest second = publishAt("2026-10-27T11:00:00Z").orElseThrow();

        assertNotEquals(first.chainHead(), second.chainPrev(),
                "a deleted entry must show up as a mismatch, not as a gap");

        // The device still publishes: silence would be indistinguishable from an agent that was
        // simply switched off. Saying something that does not line up is the useful signal.
        assertFalse(this.audit.verify().ok(), "the local chain no longer verifies either");
    }

    @Test
    void theDigestItselfIsRecordedInTheChainForTheNextHour() {
        recordEntries(2);

        publishAt("2026-10-27T10:00:00Z");

        List<AuditEntry> tail = this.audit.tail(1);
        assertEquals("AUDIT_DIGEST", tail.get(0).type(), "the device attests to what it published");
        assertTrue(tail.get(0).detail().contains("entries=2"));
        assertTrue(this.audit.verify().ok());
    }

    @Test
    void oneHourIsPublishedOnce() {
        recordEntries(1);
        assertTrue(publishAt("2026-10-27T10:00:00Z").isPresent());

        assertTrue(publishAt("2026-10-27T10:30:00Z").isEmpty());
        assertEquals(1, this.spool.peekAll().stream()
                .filter(record -> Topics.AUDIT_DIGEST.equals(record.topic())).count());
    }

    @Test
    void aRestartInTheSameHourDoesNotShiftTheGrid() {
        recordEntries(1);
        publishAt("2026-10-27T10:00:00Z");

        // A restart rebuilds the publisher at 10:20; the grid is the clock's, not the process's.
        this.publisher = new AuditDigestPublisher(this.audit, this.spool, GATEWAY, this.clock);
        assertEquals(Instant.parse("2026-10-27T10:00:00Z"), this.publisher.publishedThrough());

        recordEntries(1);
        AuditDigest next = publishAt("2026-10-27T11:00:00Z").orElseThrow();

        assertEquals(Instant.parse("2026-10-27T10:00:00Z"), next.periodStart());
        assertEquals(Instant.parse("2026-10-27T11:00:00Z"), next.periodEnd());
    }

    @Test
    void anHourThatOnlyContainsTheDigestEntryIsStillPublished() {
        recordEntries(1);
        publishAt("2026-10-27T10:00:00Z");

        // The digest wrote an entry of its own; the next hour is not empty just because no
        // message was sent, and the platform should see that rather than a silent gap.
        assertFalse(publishAt("2026-10-27T11:00:00Z").isEmpty());
    }

    private void recordEntries(int count) {
        for (int i = 0; i < count; i++) {
            this.audit.record("TEST", "em-" + i, "entry " + i);
        }
    }

    private Optional<AuditDigest> publishAt(String instant) {
        this.clock.set(Instant.parse(instant));
        return this.publisher.pulse();
    }

    /** A clock the test moves by hand; the digest grid is the only thing that reads it. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.now;
        }
    }
}
