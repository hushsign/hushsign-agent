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

## The hourly caps ("target +4070… has reached its 6-per-hour cap")

That refusal is `caps` in the same `policy.yaml`, and it is the gateway's own ceiling - the
platform does not set it, cannot change it, and only displays what the heartbeat reports:

```yaml
caps:
  perTargetPerHour: 6        # one subscriber; 0 means no ceiling
  perOperatorPerHour: 120    # one network (MCC-MNC)
  perGatewayPerHour: 300     # the whole device
```

Three properties worth knowing before changing a number here:

- **All three are checked, target first.** A command must pass every ceiling; if one refuses,
  the counts taken for the others are given back, so a refused command costs nothing.
- **A refused command is not a bug.** It exists so a mistyped cadence cannot turn into a burst
  against one subscriber - a 10-second schedule is six sends in the first minute, which is the
  entire hourly allowance for that target. That is the ceiling doing its job.
- **A failed send still counts.** The cap bounds attempts, not deliveries, because the thing it
  protects is the subscriber's network, not our bookkeeping. With a broken modem, six failed
  attempts exhaust the hour just as six delivered messages would.

The count is per **clock hour (UTC)**: `epochSecond / 3600`, not a rolling window. It is persisted
in `policy-countr*.journal` under the work directory and replayed at startup, so restarting the
agent - or a power cut - does not hand out a fresh allowance. A clock rewound past an hour
boundary reuses the bucket it left rather than opening a new one.

Requirements: Java 25 (wrapper bundles Maven).

VS Code: the Java extension (`redhat.java`) reports "Project configuration is not up-to-date
with pom.xml" through m2e after a parent-pom change. `.vscode/settings.json` sets
`java.configuration.updateBuildConfiguration` to `automatic`; a root-pom change still needs one
**Java: Update Project Configuration** for the child modules. Details and the on-disk state are
in `hushsign-platform/DEVELOPMENT.md`, "VS Code".
HushSign agent: open-source GSM edge runtime (hs-gsm, technique generators, policy, audit chain). Apache-2.0.
