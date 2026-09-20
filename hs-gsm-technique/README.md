# hs-gsm-technique — technique generators (P1-S4)

Generators for the four silent techniques of the catalog (P1-S14 decision:
WAP Push SL/SI and MWI activation were dropped because they are visible on
modern phones; the quiet MWI deactivation stays). Each generator emits a
complete SMS-SUBMIT PDU hex string (ready for `AT+CMGS`) built from the
technique's fixed template plus a destination MSISDN. Per HushSign invariant 8,
the only content a ping carries is the fixed, versioned template — destinations
are the only variable input.

| Technique | Params | Template basis |
|---|---|---|
| `SILENT_TP0` | none | HushSMS capture: TP-PID 0x40, empty user data |
| `WAP_PUSH_EMPTY` | none | HushSMS capture: port UDH to WAP port, 1 payload octet |
| `MMS_NOTIFY_EMPTY` | none | HushSMS capture: empty MMS notification |
| `MWI_DEACTIVATE` | none | HushSMS capture: MWI deactivation, no user-visible effect |

Usage:

```java
String pduHex = Technique.MWI_DEACTIVATE.generate("40756270541", null);
```

Tests:

- `TechniqueGoldenTest` — every technique reproduces the captured PDU
  **byte-for-byte** (same vectors as the hs-gsm `GoldenCorpusTest`), and every
  generated PDU parses back through the vendored codec with the expected
  fields (destination, TP-PID, TP-DCS, WAP port UDH).
- `TechniqueValidationTest` — parameterless-catalog enforcement and E.164
  destination validation (odd-length BCD padding).
