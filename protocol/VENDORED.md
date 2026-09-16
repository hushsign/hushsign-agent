# hs-protocol — vendored wire protocol

Vendored copy of `platform/libs/protocol` (v1) for the open-source agent:

- JSON Schemas v1 under `src/main/resources/protocol/schemas/v1/`
  (EmissionCommand, EmissionCancelled, TransmissionResult, DeliveryReport,
  GatewayHeartbeat, AuditDigest) + `schemas.sha256` manifest
- Typed wire records (`com.hushsign.protocol.v1.*`), Jackson setup
  (`ProtocolJson`), topic catalog (`Topics`), schema validator
  (`ProtocolValidator`) and command validator (`CommandValidator`:
  JSON Schema → freshness gate → cross-field rules)

Synced from the platform repo at the P1-S6 commit of
`hushsign-platform` (`platform/libs/protocol`); the automated drift check
(`scripts/check-protocol-drift`) lands with P1-S12.
