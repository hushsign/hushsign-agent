# hs-agent — edge runtime

Runtime skeleton for the HushSign agent: Kafka I/O, modem pool, execution
loop, policy engine, audit chain, dedupe, spool, heartbeat.

## P1-S6 — Kafka consumer layer

`com.hushsign.agent.runtime.kafka.EmissionConsumer`:

- Subscribes to `hs.emissions.{operator}` with consumer group `gw-{operator}`
  (competing gateways per operator); one instance per operator.
- **Manual commit** — offsets commit only after the batch was fully handled;
  a handler failure leaves the batch uncommitted (at-least-once redelivery,
  absorbed by the P1-S7 dedupe store). Rejected payloads (invalid / stale /
  clock-skew) are terminal and committed.
- **Pause/resume** — `pause()` pauses all assigned partitions on the polling
  thread; the broker keeps the session alive, so long serial work never
  triggers a rebalance. `resume()` restores delivery.
- **Freshness gate** — every payload passes the vendored `CommandValidator`
  (JSON Schema → freshness gate, default 300 s ± 120 s skew → cross-field
  rules). Stale and clock-skewed commands are refused on arrival.

Depends on `hs-protocol` (vendored wire protocol, see `../protocol/VENDORED.md`).
Tests use `MockConsumer`; the real-broker E2E lands in P1-S13.
