package com.hushsign.agent.runtime.heartbeat;

import com.hushsign.agent.runtime.spool.Spool;
import com.hushsign.protocol.MessageType;
import com.hushsign.protocol.ProtocolJson;
import com.hushsign.protocol.ProtocolValidator;
import com.hushsign.protocol.Topics;
import com.hushsign.protocol.v1.GatewayHeartbeat;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * P1-S9: builds the gateway heartbeat and spools it to
 * {@code hs.fleet.heartbeat.v1} (keyed by gatewayId) via the P1-S8 spool —
 * when the broker is down, heartbeats queue on disk and flush in order on
 * reconnect.
 *
 * <p>Every payload is self-validated against the vendored JSON Schema before
 * it is spooled; policy/config hashes are checked for the required 64-hex
 * shape. The sequence starts at 0 and counts up per process (monotonic
 * within a run).
 */
public final class HeartbeatProducer {

    private final HeartbeatSource source;
    private final Spool spool;
    private final Clock clock;
    private final ProtocolValidator validator = new ProtocolValidator();

    private long sequence;

    public HeartbeatProducer(HeartbeatSource source, Spool spool) {
        this(source, spool, Clock.systemUTC());
    }

    public HeartbeatProducer(HeartbeatSource source, Spool spool, Clock clock) {
        this.source = source;
        this.spool = spool;
        this.clock = clock;
    }

    /**
     * Builds, validates and spools one heartbeat.
     *
     * @return the heartbeat that was spooled
     */
    public synchronized GatewayHeartbeat pulse() {
        GatewayHeartbeat heartbeat = build();
        String json = ProtocolJson.write(heartbeat);
        validator.validate(MessageType.GATEWAY_HEARTBEAT, json); // fail before spooling garbage
        spool.enqueue(Topics.FLEET_HEARTBEAT, heartbeat.gatewayId(), json);
        return heartbeat;
    }

    public synchronized long sequence() {
        return sequence;
    }

    private GatewayHeartbeat build() {
        if (source.gatewayId() == null || source.gatewayId().isBlank()
                || source.gatewayId().length() > 128) {
            throw new IllegalArgumentException("gatewayId must be 1-128 chars");
        }
        if (!Hashes.isSha256Hex(source.policyHash())) {
            throw new IllegalArgumentException("policyHash must be 64 lowercase hex chars");
        }
        if (!Hashes.isSha256Hex(source.configHash())) {
            throw new IllegalArgumentException("configHash must be 64 lowercase hex chars");
        }
        if (source.counters() != null) {
            for (Map.Entry<String, Long> counter : source.counters().entrySet()) {
                if (counter.getValue() == null || counter.getValue() < 0) {
                    throw new IllegalArgumentException("counter " + counter.getKey() + " must be non-negative");
                }
            }
        }
        sequence++;
        return new GatewayHeartbeat(
                "1",
                source.gatewayId(),
                source.agentVersion(),
                source.policyHash(),
                source.configHash(),
                sequence,
                source.uptimeSeconds(),
                Instant.now(clock),
                source.modems(),
                source.sims(),
                source.counters());
    }
}
