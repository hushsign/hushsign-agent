#!/bin/sh
# Drift check for a (vendored) copy of the protocol schemas.
#
# Usage: check-protocol-drift.sh <schemas-dir>
#   <schemas-dir> must contain schemas.sha256 and the schemas under v1/.
#
# Exits non-zero on any missing, modified or extra schema file. Used by the
# agent repo's CI (P1-S12) to prove its vendored copy matches the canonical
# schemas published by the platform repo.
set -eu

TARGET_DIR="${1:?Usage: check-protocol-drift.sh <schemas-dir>}"
cd "$TARGET_DIR"

if [ ! -f schemas.sha256 ]; then
  echo "missing manifest: $TARGET_DIR/schemas.sha256" >&2
  exit 1
fi

# Modified / missing files (sha256sum -c fails the script on mismatch).
sha256sum -c schemas.sha256 >/dev/null

# Extra files not listed in the manifest.
ACTUAL="$(mktemp)"
EXPECTED="$(mktemp)"
trap 'rm -f "$ACTUAL" "$EXPECTED"' EXIT
find v1 -maxdepth 1 -name '*.json' -type f | sort > "$ACTUAL"
awk '{print $NF}' schemas.sha256 | sort > "$EXPECTED"
if ! diff "$EXPECTED" "$ACTUAL" >/dev/null; then
  diff "$EXPECTED" "$ACTUAL" >&2 || true
  echo "schema file set does not match the manifest" >&2
  exit 1
fi

echo "protocol schemas up to date: $TARGET_DIR"
