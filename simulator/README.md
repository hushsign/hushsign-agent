# hs-agent-simulator — mock modem + scriptable scenarios (P1-S5)

Runs the agent's modem layer against a mock modem; used in CI and demos.
Replays the four plan scenarios (success, failure, offline, policy reject),
including mid-run transitions.

- `SimModem` — a scriptable `SerialPort` that speaks just enough AT to drive
  the real hs-gsm `Modem` end-to-end: `AT`/`AT+CGSN` probe, identity surface
  (`CGMI/CGMM/CIMI/CGMR/CSQ`), and the full `AT+CMGS` → `> ` → PDU → Ctrl-Z
  handshake. Records every byte written and every PDU payload captured;
  responses for any exact command can be overridden.
- `SimScenario` — SUCCESS (`+CMGS: n` indexes), FAILURE (`+CMS ERROR: 500`),
  OFFLINE (AT answers, PDU responses never come → send timeouts),
  POLICY_REJECT (`+CMS ERROR: 304`).
- `SimulatorApp` — demo runner: `java … SimulatorApp
  [SUCCESS|FAILURE|OFFLINE|POLICY_REJECT] [technique] [destination]`.
- `SimulatedAgentApp` — P1-S13 Kafka E2E runner: the full agent pipeline
  (`EmissionConsumer → dedupe → policy → executor → SimModem`) against a real
  broker. Consumes `hs.emissions.{operator}` (group `gw-{operator}`) and
  publishes transmissions to `hs.transmissions.v1` through the on-disk spool.

Tests (`SimModemTest`) drive the real `Modem` + `Technique` stack through the
scenarios and assert state transitions (DEGRADED → ERROR quarantine), PDU
capture byte-equality, and the whole AT identity surface.

## Kafka E2E (P1-S13)

```powershell
mvnw.cmd -q install -DskipTests
$env:HS_KAFKA_BOOTSTRAP_SERVERS = 'localhost:9094'   # EXTERNAL listener (host)
mvnw.cmd -pl simulator exec:java `
  -Dexec.mainClass=com.hushsign.agent.simulator.SimulatedAgentApp `
  -Dexec.args="SUCCESS 226-10"
```

Then produce a schema-valid `EmissionCommand` on `hs.emissions.226-10` and
watch `hs.transmissions.v1` — outcomes ACCEPTED / FAILED / EXPIRED, keyed by
emissionId. The agent's spool (`target/sim-agent/spool.jsonl`) survives broker
outages and flushes in order on reconnect; stop with Ctrl-C.

Run locally (installs snapshots, then runs the demo):

```powershell
mvnw.cmd -q install -DskipTests
mvnw.cmd -q -pl simulator org.codehaus.mojo:exec-maven-plugin:3.1.0:java `
  -Dexec.mainClass=com.hushsign.agent.simulator.SimulatorApp `
  -Dexec.classpathScope=compile
```
