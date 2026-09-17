# P1-S14 spike — Huawei USB dongle on Windows 11 (driver recon)

**Date:** 2026-09-17 · **Status:** recon complete, decision: defer Windows support to P7

## Device

- Vodafone-branded Huawei dongle, `USB\VID_12D1&PID_1520` (storage/installer mode on first plug).
- Exposes only `USB Mass Storage Device` + virtual CD-ROM `F:` ("Mobile Partner", Huawei Mobile
  Partner installer). No modem, no COM ports, no network adapter until mode-switched.

## Windows 11 driver story

- The vendor package (extracted from the virtual CD) contains a switch filter
  `ew_hwusbdev.sys` that performs the storage → modem mode switch. **HVCI (Memory Integrity)
  blocks this driver**: Kernel-PnP event 219 "The driver \Driver\ew_hwusbdev failed to load".
- The rest of the package (WHQL-signed) installs fine:
  - `ewser2k.inf` + `ewusbmdm.sys` — Qualcomm serial ports (PIDs 1001/1003/1401–1405…)
  - `ew_cdcacm.inf` + `ew_cdcacm.sys` — CDC-ACM serial ports
  - `ewmdm2k.inf` — modem; `ewnet.inf`/`ew_cdcecm.inf`/`ew_wwanecm.inf` — network modes
  - `ew_hwusbdevcfg.inf` — virtual COM binding for switched PIDs (14BA/14FE/1446/1505/1580…/15xx)
- Result: after installing the switch driver, the device is claimed by it (COM5, Ports class),
  stuck in `CM_PROB_NEED_RESTART`; after reboot HVCI refuses `ew_hwusbdev.sys` and the switch
  never happens.

## What was tried (all scripted paths exhausted)

| Approach | Outcome |
|---|---|
| Eject virtual CD (`Shell.Application` verb Eject) | no mode switch (this stick needs the USB switch message) |
| Vendor driver install (`pnputil`, 6 packages) | packages accepted (WHQL) but switch filter blocked by HVCI |
| `pnputil /restart-device` to complete binding | refused — "pending system reboot" |
| SCSI pass-through on `F:` (`IOCTL_SCSI_PASS_THROUGH_DIRECT`, 31-byte Huawei message) | rejected by the CD-ROM class driver (win32 error 1306) |
| Unsigned inbox-only WinUSB INF (`Include=winusb.inf`) | rejected — "third-party INF does not contain digital signature information" |
| Self-sign a catalog | no `signtool`/`makecat` on the machine (needs Windows SDK) |
| Zadig CLI / wdi-simple extraction | Zadig 2.9 has no CLI; `wdi-simple.exe` only via its GUI |

## Decision

**Defer Windows-dongle support to P7.** The project obtains its own code-signing certificate
for release signing in P7 — with it, a tiny signed WinUSB/usbser INF can be shipped and the
whole setup becomes scriptable (no vendor binaries, no HVCI compromise, no GUI steps).

Until then the Linux/Pi path needs none of this: `usb_modeswitch` (userspace) + `cdc_acm`.
Known switch message candidates for `12d1:1520` (usb_modeswitch data):

```
55534243123456780000000000000011062000000101000100000000000000   # K3770-class
55534243123456780000000000000011060000000000000000000000000000   # generic HuaweiNewMode
```

Post-switch the stick re-enumerates as a Huawei modem PID (e.g. 14FE/1506/14AC-class); on
Windows the inbox `usbser.sys` binds standard CDC-ACM interfaces automatically.

## Action items (P7)

- Sign a WinUSB INF (switch) + optional usbser INF (serial binding) with the release cert.
- Ship a `scripts/windows-dongle-setup.ps1` (install INF → send switch message via pyusb/libusb
  or usb_modeswitch → verify COM ports).
- Re-test on this machine with HVCI enabled; document in the ops runbook.
- Keep the vendor-package inventory above for reference (driver PIDs, install order in
  `install.xml`).
