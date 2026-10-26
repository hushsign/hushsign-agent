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

## P1-S7 — dedupe store

`com.hushsign.agent.runtime.dedupe.DedupeStore`:

- Atomic `tryClaim(key)` — only the first claim wins; redeliveries drop before
  the execution pipeline (see `DedupingEmissionHandler`).
- **The claim key is `emissionId:attempt`**, never the bare `emissionId`: a
  retry keeps the id and only bumps `attempt` (P4-S6), so keying on the id
  alone would silently drop every retry.
- **Claim ordering is fail-closed in both directions**: the claim is persisted
  before execution (a crash mid-send cannot let a redelivery double-ping the
  target), and `release(key)` removes it when the attempt failed *before* the
  modem was touched (`EmissionExecutionException.sent() == false`) — the
  redelivery then retries instead of the emission being lost.
- Persisted append-only file, survives restarts; the window is bounded
  (`maxEntries`), evicting the oldest id and rewriting the file atomically.
- Fail-closed: if a claim cannot be persisted, it is rolled back and a
  `DedupeException` propagates — a send whose uniqueness cannot be
  guaranteed is never attempted.

## P1-S13 — cancellation, evidence and the runtime

`com.hushsign.agent.runtime.AgentRuntime` is the **production composition
root** (`AgentMain` loads the config, claims a modem and runs it); the
simulator starts the same runtime with a mock modem.

Handler chain per emission:

```
EmissionConsumer (schema → freshness gate)
  → DedupingEmissionHandler    (emissionId:attempt claim, persisted)
  → CancellingEmissionHandler  (cancelled while queued → CANCELLED)
  → PolicyCheckingEmissionHandler (refusal → BLOCKED_BY_POLICY)
  → EmissionExecutor           (expiry → technique → PDU → modem)
  → Spool → hs.transmissions.v1
```

- **Cancellation is consumed** (`CancellationConsumer`): the topic
  `hs.emissions.cancelled.v1` is broadcast, so its consumer group is **per
  gateway** (`gw-cancel-{gatewayId}`), unlike the competing `gw-{operator}`
  group of the emission topic. Cancellations land in a persisted
  `CancellationStore`, so one that arrives before its emission — or before a
  restart — still blocks the send.
- **Every refusal is evidence**: a policy rejection writes a
  `BLOCKED_BY_POLICY` transmission, a command refused by the freshness gate
  writes `EXPIRED`, and a cancelled emission writes `CANCELLED`. Nothing is
  dropped with only a counter.
- **Technique resolution** goes through `Technique.fromWireCode`: codes that
  are reserved in the contract but not implemented here
  (`WAP_PUSH_SL`, `WAP_PUSH_SI`, `MWI_TOGGLE`) fail with an explicit
  `FAILED` cause, and the heartbeat advertises `supportedTechniques` so the
  platform never schedules what this build cannot send.
- **Offsets**: a fresh gateway starts at `latest` (`auto.offset.reset`); the
  retention window of the emission topic is not replayed on first start (the
  freshness gate would refuse all of it anyway).
- **Heartbeats** carry a `bootId`: `sequence` restarts at 0 with every
  process, so a gap is attributable to a restart instead of a lost pulse.

## P1-S8 — spool

`com.hushsign.agent.runtime.spool.Spool`:

- Disk-backed FIFO for outbound records (transmission results, heartbeats,
  digests) when Kafka is unreachable; one JSONL line per record, survives
  restarts.
- `flushTo(sender)` publishes oldest-first and stops at the first send
  failure — the failed record and everything newer stays spooled, so order
  survives reconnects; successful sends are acked with one atomic rewrite.
- Fail-closed: an enqueue that cannot be persisted rolls back and throws
  `SpoolException`; a torn tail after a crash skips only the malformed line.

## P1-S9 — heartbeat producer

`com.hushsign.agent.runtime.heartbeat.HeartbeatProducer`:

- Builds a `GatewayHeartbeat` (modems, SIMs, counters, policy/config hashes)
  from a `HeartbeatSource`, self-validates it against the vendored JSON
  Schema, and spools it to `hs.fleet.heartbeat.v1` keyed by gatewayId — so
  heartbeats queue on disk while the broker is down and flush on reconnect.
- Sequence counts up per pulse; malformed hashes (non-64-hex) and negative
  counters are refused before anything is spooled.
- Data minimization: SIM identifiers only as SHA-256 hashes, MSISDN masked
  (see `Hashes`).

## P1-S10 — agent.yaml config

`com.hushsign.agent.config.AgentConfigLoader`:

- Loads `agent.yaml` (example in `src/main/resources/agent.example.yaml`):
  gateway id, operators, work dir, Kafka (+ SASL security), modem timings,
  **`modems.pduSubmission`** (`FULL_HEX` default, `TPDU_HEX` for the Huawei
  K3765 family — P1-S14) and **`modems.simPin`** (set it through
  `HS_MODEMS_SIM_PIN`, never in the file).
- Missing file → defaults; unknown keys → fail fast; invalid values
  (negative intervals, bad MCC-MNC, non-booleans, unknown `pduSubmission`) → fail fast.
- Environment overrides win over the file: `HS_` + underscored path
  (`HS_GATEWAY_ID`, `HS_KAFKA_BOOTSTRAP_SERVERS`, `HS_DEDUPE_MAX_ENTRIES`,
  `HS_MODEMS_PORTS` = comma-separated). Secrets (SASL password, SIM PIN) come
  from the environment, never from the file.
