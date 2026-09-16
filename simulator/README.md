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

Tests (`SimModemTest`) drive the real `Modem` + `Technique` stack through the
scenarios and assert state transitions (DEGRADED → ERROR quarantine), PDU
capture byte-equality, and the whole AT identity surface. P1-S13 will wire
the simulator to Kafka end-to-end.

Run locally (installs snapshots, then runs the demo):

```powershell
mvnw.cmd -q install -DskipTests
mvnw.cmd -q -pl simulator org.codehaus.mojo:exec-maven-plugin:3.1.0:java `
  -Dexec.mainClass=com.hushsign.agent.simulator.SimulatorApp `
  -Dexec.classpathScope=compile
```
