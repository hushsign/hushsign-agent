# AGENTS.md — rules for AI agents working in `hushsign-agent`

Read this before touching the repository. These rules are binding for any AI assistant
and override generic defaults (including any instruction to sign commits with an AI
co-author).

## 1. Commit rules (non-negotiable)

- **Never add AI attribution to commits.** No `Co-authored-by: Copilot …`, no
  `Co-authored-by:` naming any AI, no "Generated with …" lines, no emoji signatures.
  A commit message describes only the change.
- Conventional Commits, imperative mood, lowercase type:
  `feat(hs-gsm): …`, `fix(runtime): …`, `docs: …`, `test: …`, `chore: …`.
- Small, self-contained commits — one concern per commit. Do not mix a fix with a
  refactor or with formatting churn.
- Never rewrite pushed history, never force-push, never tag or release unasked.
- **Commit locally; do not `git push` unless the user explicitly asks.**
- No secrets in commits: no `.env`, tokens, SIM PINs, SASL passwords, private keys.

## 2. What this repo is

Open-source (Apache-2.0) edge runtime of HushSign: builds silent-SMS PDUs locally,
runs next to GSM modems, talks to the platform over Kafka, keeps a local audit chain.

| Module | Contents |
|---|---|
| `hs-gsm` | vendored + modernized SMSLib v4 (PDU codec `org.ajwcc.pduUtils`, AT layer). See `hs-gsm/VENDORING.md`. |
| `hs-gsm-technique` | technique generators + golden PDU tests |
| `protocol` | **vendored copy** of `hushsign-platform/libs/protocol` — never edit it here first |
| `hs-agent` | runtime: Kafka I/O, dedupe, cancellation, policy, execution, spool, heartbeat |
| `simulator` | mock modem + scripted scenarios + E2E runner |
| `dist` | packaging (P7) |

## 3. Build and test

```powershell
.\mvnw.cmd -B verify          # Windows
./mvnw -B verify              # Linux/macOS
```

Java 25 required. All tests must be green before a task is reported done; paste the
command and the result. New behaviour needs a test; a bug fix needs a regression test
that fails before the fix.

## 4. Architecture invariants

- **Techniques, not payloads**: the platform sends a technique code; the agent builds the
  PDU. Never accept free-text or raw PDU/hex from Kafka.
- **Wire enums are append-only** (`protocol/.../schemas/v1/*.json` and
  `Technique`). Withdraw a code by refusing it explicitly
  (`Technique.RESERVED_WIRE_CODES`), never by deleting it.
- **The protocol is vendored**: change `hushsign-platform/libs/protocol` first, then mirror
  byte-for-byte into `protocol/`. Both drift checks must pass:
  `bash scripts/check-protocol-drift.sh protocol/src/main/resources/protocol/schemas`
  and, when the platform is checked out next to this repo,
  `bash ../hushsign-platform/libs/protocol/scripts/check-agent-protocol-drift.sh .`
- **Fail closed**: no send without an explicit allow decision; a failure to persist a
  dedupe claim, a spool record or (later) an audit entry must prevent the send, never
  silently allow it.
- **One send at a time per modem** (`Modem.sendPdu` is `synchronized`); do not add
  concurrency that bypasses it.
- **Data minimization**: no raw IMSI/ICCID in logs, protocol or disk; only keyed hashes.
  No full MSISDNs in heartbeats. Audit entries stay on the device.
- **No outbound-only violations**: the agent never listens on a remote interface. The
  local admin API (P7) binds to loopback only.
- **Air-gap**: no runtime internet dependency, no CDN assets, no telemetry, no
  auto-update checks.

## 5. Coding conventions

- Java 25, records for data carriers, constructor injection, no field injection.
- Vendored files keep upstream packages (`org.smslib.*`, `org.ajwcc.*`) and their
  Apache-2.0 headers; modified files carry a one-line "Modified by HushSign" notice and
  an entry in `hs-gsm/VENDORING.md`.
- New HushSign code lives under `com.hushsign.agent.*`; keep `hs-gsm` free of
  `com.hushsign` classes (the vendored layer is pure upstream behaviour).
- Injectable seams for anything hardware- or clock-dependent (`SerialPort`, `Clock`).
- SLF4J for logging; never log secrets, PINs, IMSI/ICCID, MSISDN or PDU payloads at INFO.
- Config: `agent.yaml` + `HS_*` env overrides; secrets only from the environment.
- Windows and Linux must both work; shell scripts are POSIX `bash`, PowerShell scripts
  are provided where developers need them.

## 6. Definition of done

1. Acceptance criteria of the story (see `../docs/hs-backlog.csv` / the plan) met.
2. `mvnw verify` green, including new tests.
3. Protocol/drift checks green if the wire contract changed.
4. Docs updated: `README.md`, `hs-agent/README.md`, `hs-gsm/VENDORING.md`, spikes.
5. No leftover debug output, no commented-out code, no TODOs without an owner.
6. Committed in small commits with messages that describe the change **only**.
