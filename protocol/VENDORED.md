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
`hushsign-platform` (`platform/libs/protocol`).

## Drift protection (P1-S12)

This copy must never diverge from the canonical module; drift is caught in
three layers:

1. **Platform CI** (authoritative, no credentials): every platform build and
   a weekly schedule checkout this public repo and run
   `libs/protocol/scripts/check-agent-protocol-drift.sh` — Java sources under
   `com/hushsign/protocol` and all schemas must match byte-for-byte
   (LF-normalized).
2. **Agent CI** (`protocol-drift` job): clones the private platform repo and
   runs the same full check, when the `PLATFORM_REPO_TOKEN` secret (read-only,
   fine-grained PAT) is configured; fork PRs and token-less runs skip it.
3. **Agent CI** (`protocol-schemas` job) always runs the local self-check
   `scripts/check-protocol-drift.sh` (schemas vs the vendored manifest;
   `SchemaManifestTest` covers the same invariant in the build).

The one allowed difference is `com/hushsign/protocol/package-info.java`: the
canonical module keeps its package marker under
`com/hushsign/platform/protocol`, so the agent carries its own.

**To refresh this copy after a protocol change**: apply the canonical diff to
`protocol/` (Java + schemas + manifest), keep the local `package-info.java`,
and run `scripts/check-protocol-drift.{sh,ps1}` locally before committing.
