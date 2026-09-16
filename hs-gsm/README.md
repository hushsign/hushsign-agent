# hs-gsm — vendored, modernized SMSLib (P1-S1)

GSM 03.40 PDU codec + AT/modem layer vendored from upstream SMSLib v4 source
per ADR-0009. Original package names are kept for diffability; the
Service/queue/threading machinery was pruned.

- `NOTICE` — third-party attribution
- `VENDORING.md` — pinned source, file list, edit log
- `com.hushsign.agent.gsm.modem.ModemContext` — the seam between the vendored
  AT layer and the fresh modem implementation (P1-S2)

Golden PDU corpus tests land with P1-S3; technique generators with P1-S4.
