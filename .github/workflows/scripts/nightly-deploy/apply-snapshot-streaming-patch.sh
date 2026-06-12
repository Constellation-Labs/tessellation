#!/usr/bin/env bash
# snapshot-streaming.patch removes c.forkInfoStorage from Configuration.scala
# to match release/testnet's SharedConfigReader. Other branches still have
# forkInfoStorage, so applying the patch there would drop a required arg
# and break the build. Overlay the triggering ref's patch only when the
# build ref is release/testnet; otherwise clear the patch file.
#
# Env:
#   BUILD_BRANCH       — the branch being built (defaults to release/testnet)
#   TRIGGERING_REF     — the ref that triggered the workflow (for logging)

set -euo pipefail

: "${BUILD_BRANCH:=release/testnet}"
: "${TRIGGERING_REF:=unknown}"

src=.patch-source/docker/snapshot-streaming/snapshot-streaming.patch
dst=docker/snapshot-streaming/snapshot-streaming.patch

if [ "$BUILD_BRANCH" = "release/testnet" ]; then
  if [ -f "$src" ] && [ -s "$src" ]; then
    cp "$src" "$dst"
    echo "Overlaid snapshot-streaming.patch from $TRIGGERING_REF ($(wc -c < "$dst") bytes)"
  else
    echo "No snapshot-streaming.patch on triggering ref ($TRIGGERING_REF) — leaving build-ref copy in place"
  fi
else
  : > "$dst"
  echo "Cleared snapshot-streaming.patch (building $BUILD_BRANCH, not release/testnet)"
fi
