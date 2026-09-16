# P0-S7 Spike — SMSLib fork vs upstream v4

- Status: Done (feeds ADR-0009, closes open decision #1)
- Date: 2026-09-16
- Sources compared:
  - **Fork**: `thunderping/smslib` @ `e189e4c` ("Improved serial connection"), local copy `C:\Users\crist\thunderping\smslib`
  - **Upstream**: `tdelenikas/smslib` (SMSLib v4, `dev` branch) @ `dd3651a` (2018-10-29)

## 1. Lineage finding (the big one)

The fork is **not** a divergent fork of a released SMSLib artifact. It is the
same codebase as upstream SMSLib v4 `dev` (itself the v3 architecture with
updated dependencies) with:

1. all packages renamed (`org.smslib` → `com.silentservices.smslib`,
   `org.ajwcc.pduUtils` → `com.silentservices.pduUtils`),
2. five silent-ping message types added (`smslib.message.ping`),
3. the serial driver replaced with jSerialComm,
4. HTTP gateways and `IPModemDriver` pruned (132 → 89 files),
5. targeted edits to the message/codec layer (see §3).

A structural diff plus per-file diffs confirm the shared origin: e.g.
`AbstractModemDriver` differs by 75/96 lines, `Service` by 148/167, and the
PDU codec files differ almost only in the package/import renames.

## 2. What each side has

| Area | Upstream v4 dev (132 files) | Fork (89 files) |
|---|---|---|
| Architecture | v3-style `Service` singleton, gateways, queues, routing, settings | same, pruned |
| PDU codec | `org.ajwcc.pduUtils` (GSM 03.40 submit/delivery/status + WAP Push SI) | identical, renamed |
| Modem layer | `AbstractModemDriver` (AT suite) + `SerialModemDriver` over javax.comm/`nrjavaserial` + `IPModemDriver` | `AbstractModemDriver` (AT suite, 519 lines) + `SerialModemDriver` over **jSerialComm 2.9.3** (port scan, 10 s open, 8N1, blocking reads) |
| Silent pings | **none** — codec has no PID/DCS override knobs | `PingType1..5` + `PingFactory` (see §4) |
| Crypto | `crypto.AESKey`, `KeyManager`, `Inbound/OutboundEncryptedMessage` | **same code, inherited — not fork-original** |
| Licensing | Apache-2.0 `LICENSE` in repo | **no LICENSE/NOTICE files at all** |

## 3. Fork's codec/message edits (what makes pings possible)

Upstream `OutboundMessage` cannot express a silent SMS: the PDU codec hardcodes
its own PID/DCS. The fork adds exactly the knobs the technique framework needs:

- `setProtocolIdentifier(…)` → `pdu.setProtocolIdentifier` (TP-PID, e.g. `0x40`)
- `setDcsOverride(…)` → `pdu.setDataCodingScheme` (e.g. `0xC0` flash/class-0)
- `setInformationElement(…)` → UDH injection (concat/port IEs)
- `getPdu(smscNumber, requestDeliveryReport)` → public PDU generation

These are small, well-understood additions — the spike's main value is proving
**which knobs** a silent-SMS codec needs and that they are cheap to implement.

## 4. Fork's ping types 1–5 (catalogued as reference templates)

| Type | Definition (fork code) | Meaning | Maps to (v1 technique) |
|---|---|---|---|
| 1 | PID `0x40`, DCS None, 7-bit, validity 0 | classic silent SMS type 0 | **SILENT_TP0** |
| 2 | DCS None, 7-bit, UDH concat IE `01 00 00` | 0-byte payload behind a concat header | reference only |
| 3 | DCS None, 8-bit, payload `{0x00}` | 1-byte binary ping | reference only |
| 4 | DCS None, 8-bit, empty payload | 0-byte 8-bit ping | reference (WAP_PUSH_EMPTY family) |
| 5 | DCS override `0xC0`, 7-bit | class-0 flash ping | reference (class-0 variants) |

The fork's `pduUtils.wappush` (`WapPushUtils`, `WapSiPdu`, `WapSiUserDataGenerator`)
also exists upstream and is the reference basis for the `WAP_PUSH_*` generators.

## 5. Coordinate correction

`org.smslib:smslib:4.0.3` from the plan **does not exist on Maven Central**
(latest published is `3.7.1`). SMSLib v4 lives only as the unmaintained
source repo (`tdelenikas/smslib`, last commit 2018). Vendoring therefore means
**vendoring source from the GitHub dev branch**, not resolving an artifact.

## 6. Recommendation (confirms the plan's default)

1. **Base = upstream source vendor** (`tdelenikas/smslib@dd3651a`, Apache-2.0)
   into `hs-gsm`, keeping the PDU codec and the AT/modem semantics, with the
   original `org.ajwcc`/`org.smslib` package names (keeps future upstream diffs
   readable). Drop per ADR-0008: `Service`/queues/threading/routing/settings,
   HTTP gateways, and **crypto/encrypted messages (present in both fork and
   upstream — excluded by invariant 8 either way)**.
2. **Port the fork's proven semantics fresh, not its code**:
   - serial I/O: injectable port abstraction, jSerialComm, port scan via
     `AT+CGSN`, 10 s open timeout, 8N1, blocking reads with timeout;
   - codec knobs from §3, written fresh on top of the vendored codec.
   The fork has no license files and renamed packages — vendoring it would
   create attribution debt for zero technical gain.
3. **Carry the ping types as versioned technique templates** (PID/DCS/UDH
   combinations from §4), validated by golden tests from public HushSms
   capture vectors — not by re-running fork code.

## 7. Risks

- Upstream is unmaintained (2018): vendoring means we own it — acceptable, the
  surface we keep (codec + AT) is stable and frozen in golden tests.
- jSerialComm API changes: pin the version; the driver is isolated behind an
  injectable `SerialPort` interface anyway (P1-S2).
- Fork's package renames break naive three-way diffs: keep upstream names in
  `hs-gsm` and re-derive the fork's knobs from behavior, not from its diff.
