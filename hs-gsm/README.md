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

Golden PDU corpus tests land with P1-S3; technique generators with P1-S4.

