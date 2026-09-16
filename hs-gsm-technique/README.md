# hs-gsm-technique — technique generators (P1-S4)

Generators for the six silent-SMS ping techniques of the initial catalog
(plan Appendix A). Each generator emits a complete SMS-SUBMIT PDU hex string
(ready for `AT+CMGS`) built from the technique's fixed template plus a
destination MSISDN. Per HushSign invariant 8, the only content a ping carries
is the fixed, versioned template — destinations are the only variable input.

| Technique | Params | Template basis |
|---|---|---|
| `SILENT_TP0` | none | HushSMS capture: TP-PID 0x40, empty user data |
| `WAP_PUSH_EMPTY` | none | HushSMS capture: port UDH to WAP port, 1 payload octet |
| `WAP_PUSH_SL` | `WapPush(scheme)` | HushSMS captures: no/HTTP/HTTPS prefix variants |
| `WAP_PUSH_SI` | `WapPush(scheme)` | HushSMS captures: no/HTTP/HTTPS prefix variants |
| `MWI_TOGGLE` | `MwiToggle(active, count)` | HushSMS captures: deactivate, count 1, count 123 |
| `MMS_NOTIFY_EMPTY` | none | HushSMS capture: empty MMS notification |

Usage:

```java
String pduHex = Technique.MWI_TOGGLE.generate(
        "40756270541", new TechniqueParams.MwiToggle(true, 123));
```

Tests:

- `TechniqueGoldenTest` — every technique with the exact capture parameters
  reproduces the captured PDU **byte-for-byte** (same vectors as the hs-gsm
  `GoldenCorpusTest`), and every generated PDU parses back through the
  vendored codec with the expected fields (destination, TP-PID, TP-DCS,
  WAP port UDH).
- `TechniqueValidationTest` — parameter-shape enforcement, MWI count bounds
  (one octet), E.164 destination validation, odd-length BCD padding.
