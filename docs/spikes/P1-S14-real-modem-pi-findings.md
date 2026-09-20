# P1-S14 spike — real modem on the Pi: findings

**Date:** 2026-09-18 · **Status:** spike executed, send blocked by dead SIM reader on the only available stick · **Component:** agent-gsm (`spike`)

## 1. Objective

Send one `SILENT_TP0` with `hs-gsm` through a real Huawei K3765 modem on the field Raspberry Pi, capture it with PduSpy/HushSms on a receiving phone, and record findings.

## 2. Result

Two field findings, one per stick:

1. **Stick #1 (K3765 rev 11.126.03.09.00): the SIM reader is defective.** Every
   SIM-dependent AT command returns `+CME ERROR: 13` on two OSes with two SIMs;
   Huawei's own diagnostic says `^SYSINFO sim_state=0`. Not software.
2. **Stick #2 (K3765 rev 11.126.03.07.00): SIM reader works, but the firmware
   cannot submit PDU-mode SMS.** Text-mode SMS sends fine (received on the
   capture phone), while every well-formed PDU variant is rejected with
   `+CMS ERROR: 304`. Silent SMS requires PDU mode → this stick cannot carry
   `SILENT_TP0` either.

Both failures are hardware-level; the hs-gsm software pipeline is field-clean
(open, init, PDU generation, honest error surfacing), with the defects found
during the spike fixed and regression-tested (below).

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
== P1-S14 real modem spike: COM7 -> 40756270541
modem open on COM7, state=READY
SILENT_TP0 PDU (14 octets): 0021000B910457260745F1400000
COM7 ERR==> +CMS ERROR: 304
SPIKE RESULT: FAILED - modem COM7 rejected the PDU
```

PDU-mode rejection matrix on stick #2 (all `+CMS ERROR: 304`): full PDU SMSC=00,
explicit-SMSC PDU, MR=01, echo-off, TPDU-only length, FO=0x11 with relative validity
(VPF=10), after `AT+CSMS=1` and `AT+CPMS="SM","SM","SM"`. Mode switch is honest
(`AT+CMGF=0` sticks and reads back 0 — no text-mode pinning). Text mode: accepted.
Re-flashing the stick did not change the revision string (`11.126.03.07.00`) nor the
behaviour (the only visible change is the PDU bytes now being echoed before the 304).
Conclusion: this firmware family cannot submit PDU-mode SMS — silent SMS is
impossible on the K3765 units available.

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

- Re-run the spike on hardware that passes BOTH acceptance checks (SIM read + PDU-mode
  submission) to complete the P1-S14 acceptance (send + PduSpy/HushSms capture + PDU
  byte-match vs golden vectors).
- Re-enable HVCI on the Windows machine after the driver test window.
