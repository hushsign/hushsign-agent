# hs-gsm — vendored, modernized SMSLib (P1-S1)

GSM 03.40 PDU codec + AT/modem layer vendored from upstream SMSLib v4 source
per ADR-0009. Original package names are kept for diffability; the
Service/queue/threading machinery was pruned.

- `NOTICE` — third-party attribution
- `VENDORING.md` — pinned source, file list, edit log
- `com.hushsign.agent.gsm.modem.ModemContext` — the seam between the vendored
  AT layer and the fresh modem implementation

## Modem layer (P1-S2)

Fresh, injectable serial I/O + modem lifecycle on top of the vendored AT layer.

- `serial.SerialPort` / `serial.SerialPortFactory` — injectable serial I/O
  seam (implemented by `serial.JSerialCommSerialPort` /
  `serial.JSerialCommPortFactory` over jSerialComm 2.11)
- `modem.ModemConfig` — gateway id, SIM pins, timings (baud 115200, open
  timeout, probe retries, response timeout, error quarantine threshold)
- `modem.JSerialModemDriver` — binds the port to the AT layer (8N1, blocking
  reads), starts the background poll reader, and overrides the vendored
  timing defaults; `char_wait_unit=0` batches each AT command into one port
  write (modern modules accept full-line bursts)
- `modem.Modem` — `open(port, config)` probes `AT` + `AT+CGSN` (with
  retries), `sendPdu(hex)` is serialized per modem and tracks
  READY/BUSY/DEGRADED/ERROR state; consecutive failures quarantine the modem
- `modem.ModemScanner` — scans serial ports for the first answering modem

## Golden PDU corpus (P1-S3)

`GoldenCorpusTest` parses 14 PDU capture vectors from the product owner's
HushSMS session (cross-checked with PduSpy): normal SMS, Class 0 flash,
silent Type 0, WAP push (empty/SL/SI × no-prefix/http/https),
MMS_NOTIFY_EMPTY and MWI (deactivate, count 1, count 123). Assertions cover
the full header (TP-MTI, TP-DA, TP-PID, TP-DCS, TP-UDL, TP-UDHI) and the
user-data bytes byte-for-byte, plus UDH ports for the WAP family.

Notes on the captures:

- HushSMS omits the SMSC-info field when no SMSC is configured; the corpus
  prefixes the zero-length SMSC octet (`00`) the vendored parser expects.
- Class 0 is sent with DCS `0x18` but 7-bit-packed text (TS 23.038 would
  read `0x18` as UCS2) — the corpus asserts raw bytes and decodes the
  payload explicitly.
- Generators reproduce the normal/silent captures; for Class 0 the strict
  generator emits UCS2, so only the DCS round-trip is asserted.

Technique generators (SILENT_TP0, WAP_PUSH_EMPTY/SL/SI, MWI_TOGGLE,
MMS_NOTIFY_EMPTY) live in `hs-gsm-technique` (P1-S4) and are verified
byte-for-byte against this corpus.

