#!/usr/bin/env bash
#
# compute-rollback-anchor.sh — pick the standalone-restart rollback anchor from mirrored
# GL0 data and trim the incomplete tail, so `run-rollback <hash>` loads a complete state.
#
#   deploy/compute-rollback-anchor.sh <node-ssh> <live-peer-ip> [gl0-data-dir]
#
# After the data mirror + swap (tn-data -> gl0-data), the newest incremental snapshots can
# sit ABOVE the newest complete state in snapshot_info (the node was copied mid-tip). A node
# can only roll back to an ordinal it has full state for, so the anchor is:
#
#   anchor = max ordinal present in snapshot_info      (newest complete state)
#
# The anchor's snapshot hash is resolved O(1) from a LIVE peer's API
# (http://<peer>:9000/global-snapshots/<ordinal>/hash) — the local hash/ tree is prefix-
# bucketed with millions of files, so a reverse inode lookup there is not viable. Same chain
# => same hash, so any live peer that still retains the ordinal is authoritative.
#
# We then delete every incremental_snapshot/ordinal entry ABOVE the anchor (bucketed by
# 20000; only the top bucket(s) are touched) so the node's local tip becomes the anchor, and
# write the hash to <base>/.last-snapshot-hash where the deploy's rollback path reads it.
# (hash/ hard-links above the anchor are left as harmless orphans — the node indexes by
# ordinal/, so they never resurface as a tip.)
#
# Env:  DRY_RUN=true   compute + report only; do not trim or write the hash
#
# Run once per node (their tips differ by a few ordinals; each gets its own anchor).

set -euo pipefail

NODE="${1:?usage: compute-rollback-anchor.sh <node-ssh> <live-peer-ip> [gl0-data-dir]}"
PEER="${2:?need a live GL0 peer ip (e.g. an external validator) to resolve the anchor hash}"
DIR="${3:-/opt/tessellation/gl0-data}"
DRY_RUN="${DRY_RUN:-false}"

echo "[anchor] $NODE : anchor from $DIR, hash via $PEER:9000 (dry_run=$DRY_RUN)"

ssh "$NODE" "DIR='$DIR' PEER='$PEER' DRY_RUN='$DRY_RUN' bash -s" <<'REMOTE'
set -euo pipefail
SI="$DIR/snapshot_info"
INC="$DIR/incremental_snapshot"
base="$(dirname "$DIR")"

[ -d "$SI" ] && [ -d "$INC/ordinal" ] || {
  echo "  ERROR: expected $SI and $INC/ordinal — is $DIR the swapped GL0 data?" >&2; exit 1; }

# Newest complete state.
max_si="$(ls -1 "$SI" 2>/dev/null | grep -E '^[0-9]+$' | sort -n | tail -1)"
[ -n "$max_si" ] || { echo "  ERROR: no numeric ordinals under $SI" >&2; exit 1; }

# Resolve the anchor snapshot hash from the live peer (O(1); local hash/ is un-scannable).
anchor_hash="$(curl -sf --max-time 10 "http://$PEER:9000/global-snapshots/$max_si/hash" 2>/dev/null | tr -d '"[:space:]')"
[ -n "$anchor_hash" ] || { echo "  ERROR: could not resolve hash for ordinal $max_si from live peer $PEER:9000 (reachable? still retains that ordinal?)" >&2; exit 1; }

# Collect incremental ordinal entries strictly above the anchor. Buckets step by 20000, so
# only buckets >= the anchor's bucket can hold anything above it.
mb="$(ls -1 "$INC/ordinal" 2>/dev/null | grep -E '^[0-9]+$' | sort -n | awk -v m="$max_si" '$1<=m' | tail -1)"
above=()
for b in $(ls -1 "$INC/ordinal" 2>/dev/null | grep -E '^[0-9]+$' | awk -v mb="$mb" '$1>=mb'); do
  for o in $(ls -1 "$INC/ordinal/$b" 2>/dev/null | grep -E '^[0-9]+$' | awk -v m="$max_si" '$1>m'); do
    above+=("$INC/ordinal/$b/$o")
  done
done

echo "  anchor ordinal = $max_si"
echo "  anchor hash    = $anchor_hash"
echo "  incremental ordinal entries above anchor = ${#above[@]}"

if [ "$DRY_RUN" = "true" ]; then
  echo "  DRY_RUN — not trimming, not writing .last-snapshot-hash"
  exit 0
fi

for f in "${above[@]}"; do rm -f "$f"; done
echo "  trimmed ${#above[@]} ordinal entries above $max_si"

printf '%s\n' "$anchor_hash" > "$base/.last-snapshot-hash"
echo "  wrote $base/.last-snapshot-hash ($anchor_hash)"
REMOTE
