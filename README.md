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

## Why a technique is refused as "not in the allow list"

Every command is checked against the deployment's `policy.yaml` before the modem is touched, so a
refusal is answered by that file and nothing else - not by the platform catalogue, not by the
console. A transmission recorded as `BLOCKED_BY_POLICY` names the rule it hit; the two that
concern a single technique are:

```yaml
techniques:
  allow:
    - SILENT_TP0
    # ... add the code to permit it

visibleSms: true      # second key, only for SMS_DELIVERY
```

`SMS_DELIVERY` needs both. It is the one technique that produces a message a subscriber can read,
so listing it under `techniques.allow` is deliberately not enough on its own: a deployment that
pastes a whole catalogue into the allow list must not switch visible messaging on by accident.
Leave `visibleSms: false` outside a field test.

The file is read once at startup - restart the unit (`systemctl --user restart hs-agent`) after
editing it, and the startup line in the journal prints the policy that is actually in force.
Ceilings still apply afterwards: `caps.perTargetPerHour` is what stops a mistyped cadence turning
into a burst against one number.

Requirements: Java 25 (wrapper bundles Maven).

VS Code: the Java extension (`redhat.java`) reports "Project configuration is not up-to-date
with pom.xml" through m2e after a parent-pom change. `.vscode/settings.json` sets
`java.configuration.updateBuildConfiguration` to `automatic`; a root-pom change still needs one
**Java: Update Project Configuration** for the child modules. Details and the on-disk state are
in `hushsign-platform/DEVELOPMENT.md`, "VS Code".
HushSign agent: open-source GSM edge runtime (hs-gsm, technique generators, policy, audit chain). Apache-2.0.
