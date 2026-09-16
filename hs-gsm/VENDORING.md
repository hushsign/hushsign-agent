# hs-gsm vendoring record (P1-S1)

Per ADR-0009, `hs-gsm` vendors **upstream SMSLib source**, not the internal
fork, and ports the fork's field-proven semantics fresh (P1-S2 onward).

## Source

- Repository: https://github.com/tdelenikas/smslib (SMSLib v4, `dev` branch)
- Pinned commit: `dd3651a4105498032bb14add528ab4362d991823` (2018-10-29)
- License: Apache-2.0 (see `NOTICE`)
- Package names kept as upstream (`org.smslib`, `org.ajwcc.pduUtils`) so
  future upstream diffs stay readable.

## Vendored files (20)

- `org.ajwcc.pduUtils.gsm3040.*` + `.ie.*` — complete GSM 03.40 codec
  (submit/delivery/status-report PDUs, UDH information elements)
- `org.ajwcc.pduUtils.wappush.*` — WAP Push SI helpers
- `org.smslib.message.MsIsdn` — E.164 address model (codec dependency)
- `org.smslib.gateway.modem.ModemResponse`, `DeviceInformation`,
  `org.smslib.core.Capabilities`, `modem.driver.AbstractModemDriver` — the
  AT/modem layer
- `org.smslib.helper.Common` — TRIMMED: only `countSheeps` and `isNullOrEmpty`
- `modem.properties` — AT wait/timing defaults (upstream resource)

## Deliberately NOT vendored

`Service` singleton, callbacks, queues, threading, routing, groups, settings,
HTTP gateways, IPModemDriver, the `Modem` aggregate, `MessageReader`, the
message model beyond `MsIsdn`, crypto/encrypted messages (invariant 8).

## Edit log (minimal, documented)

1. `AbstractModemDriver` — replaced the `Modem` aggregate with the
   `com.hushsign.agent.gsm.modem.ModemContext` seam (the only state the AT
   layer reads: device information, SIM PINs, capabilities).
2. `AbstractModemDriver` — removed the `Service.getCallbackManager()`
   inbound-call registration; the AT behaviour (hang up on `+CLIP`) is kept.
3. `AbstractModemDriver` — `modem.properties` is now optional (warn instead
   of fail when absent).
4. `Capabilities` — removed `matches(OutboundMessage)`, which belonged to the
   routing machinery.
5. `Common` — trimmed to the two used helpers.
6. `AbstractModemDriver` — added protected `setIn`/`setOut` so the fresh
   serial implementation (P1-S2) can bind its streams from its own package.

## Modernization (P1-S1 baseline)

- Java 21 (upstream was Java 8-era; compiles unchanged).
- slf4j-api 2.x for logging; no other runtime dependencies.
