# P1-S14 spike — real modem on the Pi: findings

**Date:** 2026-09-18 · **Status:** spike executed, send blocked by dead SIM reader on the only available stick · **Component:** agent-gsm (`spike`)

## 1. Objective

Send one `SILENT_TP0` with `hs-gsm` through a real Huawei K3765 modem on the field Raspberry Pi, capture it with PduSpy/HushSms on a receiving phone, and record findings.

## 2. Result

**ACCEPTANCE MET** — SILENT_TP0 sent through the real K3765 with the hs-gsm stack and
captured on the receiving phone (`SPIKE RESULT: sent OK, modem message index=19`).

The journey exposed three hardware truths and three software defects:

1. **Stick #1 (rev 11.126.03.09.00): dead SIM reader** (`+CME ERROR: 13` on two OSes,
   two SIMs, `^SYSINFO sim_state=0`).
2. **Stick #2 (rev 11.126.03.07.00): SIM fine, but the firmware demands a non-standard
   PDU submission form** — TPDU as hex text with the SMSC octet dropped. The standard
   full form (and binary form) is rejected with `+CMS ERROR: 304`.
3. **The old system worked because it configured an SMSC number**, which made its length
   math drop the SMSC octet — accidentally matching the firmware's expectation.

Software defects found and fixed during the spike: missing modem init, swallowed
refusals, prompt-wait echo race, split-frame byte eating, and the two K3765 submission
quirks (TPDU-only hex + dangling-comma `+CMGS` index) — all regression-tested.

## 3. Evidence

### 3.1 Modem + serial layer healthy (both OSes)

```
ATI: Manufacturer: huawei, Model: K3765, Revision: 11.126.03.09.00, IMEI: 351617046662843
     +GCAP: +CGSM,+FCLASS,+DS   OK
```

`AT`, `ATI`, `AT+CFUN=1`, `AT+CFUN=1,1` all answer. On Windows the vendor drivers bind all
interfaces OK (COM6 secondary modem, COM7 modem, COM9 diagnostics; NDIS + storage up).

### 3.2 SIM gate closed everywhere

| Probe | Linux Pi (`/dev/ttyUSB0`) | Windows 11 (COM6/COM7, vendor driver) |
|---|---|---|
| `AT+CPIN?` | `+CME ERROR: 13` | `+CME ERROR: SIM failure` |
| `AT+CIMI` / `AT+CSQ` / `AT+CREG?` / `AT+CMGF?` / `AT+CMGS` | `+CME ERROR: 13` | `+CME ERROR: SIM failure` |
| `AT^SYSINFO` | `4,0,0,0,255,,0` (sim_state=0) | `4,0,0,0,255,,0` (sim_state=0) |

Physical attempts exhausted: SIM reseat, both tray orientations, cold USB power cycle,
`AT+CFUN=1`, `AT+CFUN=1,1` full reset — all still `CME 13`. Cross-test with a second,
older SIM (different operator/vintage) on Windows: same `+CME ERROR: SIM failure` and
`sim_state=0` — ruling out "SIM too new" as the cause. Verdict: the K3765's SIM reader
is defective. The K3765's blinking-green-every-few-seconds LED is the **no-SIM** pattern,
not "registered".

### 3.3 hs-gsm on the real modem (after fixes)

Stick #2 (rev 11.126.03.07.00), normal SMS via text mode: **delivered** (`+CMGS: 9,
"26/09/20,20:00:35+12"`). Silent PDU through the full hs-gsm stack:

```
== P1-S14 real modem spike: COM6 -> 40756270541
modem open on COM6, state=READY
SILENT_TP0 PDU (14 octets): 0021000B910457260745F1400000
SPIKE RESULT: sent OK, modem message index=19, state=READY
```

Captured on the receiving phone with PduSpy — Type-0 silent SMS received.

**Full-catalog sweep (same modem, same destination, `RealModemTechniqueSweep`):**
all six techniques accepted by the modem — SILENT_TP0 (idx 20), WAP_PUSH_EMPTY (21),
WAP_PUSH_SL (22), WAP_PUSH_SI (23), MWI_TOGGLE (24), MMS_NOTIFY_EMPTY (25).
Field-observed effects on a modern phone: Type-0 / empty-WAP / empty-MMS-notify are
fully silent; WAP SL opens the URL (error toast when unreachable), WAP SI renders as
a service message, MWI toggles the voicemail icon (reverted with `MWI_OFF`).

**Root cause of the `304` saga:** this firmware accepts `AT+CMGS` only in the
*PCUI form* — the TPDU as ASCII hex text with the zero-length SMSC octet dropped
and the length reduced by one. The full-PDU form, binary form, explicit-SMSC form
and validity-period variants were all rejected (`+CMS ERROR: 304`). The old field
system sent hex text and configured an SMSC number, which made its length math drop
the SMSC octet — accidentally the winning recipe. Fix: `ModemConfig.pduSubmission =
PduSubmission.TPDU_HEX` (default stays the standard full form). Additionally this
firmware answers `+CMGS: n,` with a dangling comma — the index parser now tolerates it.

Before the fixes the same stick reported a misleading `send failed on ttyUSB0`.

## 4. Defects found and fixed (all regression-tested)

1. **`Modem.open` skipped the modem init.** Probe (`AT`/`AT+CGSN`) was the whole story —
   no SIM/PIN check, no network registration, no PDU-mode switch. A modem that could not
   read its SIM still claimed `READY`. → `ModemConfig.initializeOnOpen` (default on;
   `probeOnly()` used by `ModemScanner`), `Modem.open` runs `initializeModem()` fail-closed.
2. **`AT+CMGS` prompt wait raced the command echo.** The wait ended as soon as the buffer
   was non-empty (the echo satisfies that immediately) and then wiped everything, including
   the modem's refusal, with `clearResponses()`. PDU bytes went to a modem that had never
   sent `>`. → new `waitForPrompt()`: waits for the actual `>` (echo irrelevant), surfaces
   `+CMS`/`+CME`/`ERROR` refusals verbatim as `ModemException`.
3. **Refusals were swallowed into a generic "send failed".** The modem's error line was
   dropped by `getResponse()` and the caller learned nothing. → `lastErrorLine` is recorded
   (upstream `getResponseData()` semantics unchanged) and the SIM-status give-up names the
   exact response (`'+CME ERROR: SIM failure'`).
4. **`clearResponses()` raced the poll reader.** Late-drained bytes could shift the next
   command's dialogue (test flakiness on fast timings). → clear now settles for one full
   poll cycle; poll reader interval driven by `ModemConfig.pollWaitMs`.
5. **`SimModem` answered `AT+CPIN?` with bare `OK`** (no `+CPIN: READY` line), which the
   real init sequence correctly treats as "no SIM status". → mock now replies like a real
   modem.

Tests: `ModemTest` +2 (full init sequence incl. PDU-mode + rssi; SIM-failure init fails
closed and names the refusal), `sendSurfacesTheModemRefusalVerbatim` covers defect 2/3.
`hs-gsm` + `simulator` suites green (60 + 8).

## 5. Windows bring-up (spike by-product)

- Core Isolation (HVCI) disabled for the test → vendor `ew_hwusbdev.sys` switch filter and
  `hwdatacard`/`ewusbmdm.sys` serial drivers load. **Re-enable HVCI after testing.**
- Vendor package `Driver\X64`: `ew_hwusbdev.inf` (mode switch), `ewmdm2k.inf` (modem),
  `ewser2k.inf` (secondary port), `ewnet.inf` (NDIS) installed via `pnputil /add-driver
  /install` — the filter auto-switches 1520→1465 on plug.
- Tooling committed under `agent/scripts/`: `at_probe.py` (Linux), `at_probe_win.py`
  (Windows, skips BTHENUM ports), `at_huawei_diag.py` (`^SYSINFO`), `switch_dongle.py`
  (pyusb/libusb control-transfer switch for when no vendor filter is available).
- Note: the earlier self-signed WinUSB package was removed from the driver store
  (`pnputil /delete-driver oem60/oem62/oem63.inf`); libusb cannot open the storage-mode
  device without a WinUSB binding — the vendor filter path is the one to keep.

## 6. Decisions for P7 (field test)

- **Hardware acceptance test is now two-step.** Before buying/using any stick:
  1. SIM check — `AT+CPIN?` → `+CPIN: READY` and `AT^SYSINFO` → `sim_state` ≠ 0.
  2. **PDU-mode check** — submit a normal SMS PDU via `AT+CMGS` in PDU mode;
     `+CMS ERROR: 304` on all PDU variants = firmware can't do PDU mode =
     cannot carry silent SMS. `scripts/send_sms.py` / `scripts/pdu_variants.py`
     automate both.
- Prefer Huawei **E3372/E3531/K3770** (newer firmware, PDU mode reliable); the
  K3765 family has now failed two independent ways (dead SIM reader, broken
  PDU submission).
- **Process:** run the SIM/network probe before the send; only send when
  `+CPIN: READY` and `^SYSINFO sim_state` ≠ 0. The fixed `Modem.open` enforces
  this (fail-closed).
- Keep the green-LED semantics in the runbook: blinking green ≈ no SIM, not "registered".

## 7. Open items

- Re-enable HVCI on the Windows machine after the driver test window.
- (Optional hardening) capture the receiving phone's PDU log and diff it byte-for-byte
  against the golden SILENT_TP0 vector for the final evidence appendix.
