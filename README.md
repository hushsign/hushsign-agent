# HushSign Agent

Open-source **edge runtime** of the HushSign platform. Runs next to GSM modems/SIMs
(e.g. on a Raspberry Pi), builds ping PDUs locally from technique intents, enforces a
customer-owned policy file, and records a tamper-evident hash-chained audit log.

**Apache-2.0** — the customer can read, build, and audit every byte of the modem path.

## Modules

| Module | Contents |
|---|---|
| `hs-gsm` | modernized SMSLib v4: PDU codec + serial/AT driver behaviour |
| `hs-gsm-technique` | technique generators (`SILENT_TP0`, `WAP_PUSH_*`, `MWI_*`, …) with golden PDU tests |
| `hs-agent` | runtime: Kafka I/O, modem pool, execution loop, policy engine, audit chain, dedupe, spool, heartbeat |
| `simulator` | mock-modem harness for CI and demos |
| `dist` | multi-arch container, systemd unit, example configs, install script |

## Build

```bash
./mvnw verify        # Windows: .\mvnw.cmd verify
```

Requirements: Java 21 (wrapper bundles Maven).
HushSign agent: open-source GSM edge runtime (hs-gsm, technique generators, policy, audit chain). Apache-2.0.
