# HushSign Agent

Open-source **edge runtime** of the HushSign platform. Runs next to GSM modems/SIMs
(e.g. on a Raspberry Pi), builds ping PDUs locally from technique intents, enforces a
customer-owned policy file, and records a tamper-evident hash-chained audit log.

**Apache-2.0** — the customer can read, build, and audit every byte of the modem path.

## Modules

| Module | Contents |
|---|---|
| `hs-gsm` | modernized SMSLib v4: PDU codec + serial/AT driver behaviour |
| `hs-gsm-technique` | technique generators (`SILENT_TP0`, `WAP_PUSH_EMPTY`, `MMS_NOTIFY_EMPTY`, `MWI_DEACTIVATE`) with golden PDU tests |
| `hs-agent` | runtime: `AgentMain` + `AgentRuntime` (Kafka I/O, dedupe, cancellation, policy, execution, spool, heartbeat) |
| `simulator` | mock-modem harness for CI and demos |
| `dist` | multi-arch container, systemd unit, example configs, install script |

## Build

```bash
./mvnw verify        # Windows: .\mvnw.cmd verify
```

Requirements: Java 25 (wrapper bundles Maven).

VS Code: the Java extension (`redhat.java`) reports "Project configuration is not up-to-date
with pom.xml" through m2e after a parent-pom change. `.vscode/settings.json` sets
`java.configuration.updateBuildConfiguration` to `automatic`; a root-pom change still needs one
**Java: Update Project Configuration** for the child modules. Details and the on-disk state are
in `hushsign-platform/DEVELOPMENT.md`, "VS Code".
HushSign agent: open-source GSM edge runtime (hs-gsm, technique generators, policy, audit chain). Apache-2.0.
