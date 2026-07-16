#!/usr/bin/env bash
# Remote stop/cleanup for compose-runner-down.sh
# Expects REMOTE_NODES, REMOTE_CLEAN, REMOTE_DIR to be set.

set -e

IFS=',' read -ra ALL_NODES <<< "$REMOTE_NODES"
if [ "${#ALL_NODES[@]}" -ge 4 ]; then
  NODES=("${ALL_NODES[@]:0:3}")
  SS_NODE="${ALL_NODES[3]}"
else
  NODES=("${ALL_NODES[@]}")
  SS_NODE=""
fi
COMPOSE="docker compose -f docker-compose.yaml -f docker-compose.host.yaml"
DIR="$REMOTE_DIR"
GENESIS="${NODES[0]}"

# ---------------------------------------------------------------------------
# STEP 1 (chain-preserving down only): capture the CURRENT head's rollback hash
# FIRST — while every node is still up — before stopping anything.
#
# The next deploy's run-rollback rolls the chain back to this hash. A STALE anchor
# (left from a much earlier ordinal) rolls the whole chain back hundreds of ordinals,
# which re-forges the tip onto a DIFFERENT line than snapshot-streaming already
# ingested and crash-loops it on the block-explorer DB (unique-violation on ordinal).
# So the capture must be reliable: use the tiny /latest/ordinal + /<ordinal>/hash
# endpoints, retry, and ABORT (non-zero) if a fresh hash can't be obtained — the
# caller (compose-runner-down / testnet-deploy) must NOT swallow this failure.
# Run this BEFORE any stop so an unrelated stop hiccup can't skip it under `set -e`.
# ---------------------------------------------------------------------------
if [ "$REMOTE_CLEAN" != "true" ]; then
  ANCHOR_HASH=""; ANCHOR_ORD=""
  for attempt in 1 2 3 4 5; do
    ANCHOR_ORD=$(ssh "$GENESIS" "curl -sf --max-time 10 http://localhost:9000/global-snapshots/latest/ordinal 2>/dev/null" \
      | grep -oE '[0-9]+' | head -1 || echo "")
    if [ -n "$ANCHOR_ORD" ]; then
      ANCHOR_HASH=$(ssh "$GENESIS" "curl -sf --max-time 10 http://localhost:9000/global-snapshots/$ANCHOR_ORD/hash 2>/dev/null" \
        | tr -d '"[:space:]' || echo "")
    fi
    printf '%s' "$ANCHOR_HASH" | grep -Eiq '^[0-9a-f]{64}$' && break
    ANCHOR_HASH=""
    echo "  rollback-anchor capture attempt ${attempt}/5 failed; retrying in 5s..." >&2
    sleep 5
  done
  if [ -n "$ANCHOR_HASH" ]; then
    ssh "$GENESIS" "echo '$ANCHOR_HASH' > $DIR/.last-snapshot-hash"
    echo "Saved rollback anchor at ordinal $ANCHOR_ORD: ${ANCHOR_HASH:0:16}..."
  else
    echo "ERROR: could not capture the live head snapshot hash from $GENESIS after 5 attempts." >&2
    echo "       Aborting the down: proceeding would leave a stale $DIR/.last-snapshot-hash and the" >&2
    echo "       next run-rollback would roll the chain back to an ancient anchor, fork the tip, and" >&2
    echo "       crash-loop snapshot-streaming. Restore node reachability (or pass --clean) and retry." >&2
    exit 1
  fi
fi

# ---------------------------------------------------------------------------
# STEP 2: best-effort stop of everything. Never fail the down over a stop hiccup —
# the rollback anchor is already safely captured above.
# ---------------------------------------------------------------------------
# Stop snapshot-streaming first (if 4th node)
if [ -n "$SS_NODE" ]; then
  echo "Stopping snapshot-streaming on $SS_NODE"
  ssh "$SS_NODE" "if [ -f $DIR/snapshot-streaming/docker-compose.yaml ]; then cd $DIR/snapshot-streaming && docker compose down 2>&1 | grep -E '(Stopped|Removed)' || true; fi" || true
  if [ "$REMOTE_CLEAN" = "true" ]; then
    ssh "$SS_NODE" "rm -rf $DIR/snapshot-streaming/data && mkdir -p $DIR/snapshot-streaming/data; docker volume rm ss-pgdata block-explorer-node-modules 2>/dev/null" || true
    echo "  $SS_NODE data wiped"
  fi
fi

# Stop tx-sender on genesis node
ssh "$GENESIS" "docker rm -f tx-sender 2>/dev/null" || true

for h in "${NODES[@]}"; do
  echo "Stopping $h"
  ssh "$h" "if [ -d $DIR ]; then cd $DIR && $COMPOSE --profile l0 --profile l1 down 2>&1 | grep -E '(Stopped|Removed)' || true; fi" || true
  if [ "$REMOTE_CLEAN" = "true" ]; then
    ssh "$h" "rm -rf $DIR/gl0-data $DIR/gl1-data $DIR/gl0-logs $DIR/gl1-logs $DIR/.last-snapshot-hash && mkdir -p $DIR/gl0-data $DIR/gl1-data $DIR/gl0-logs $DIR/gl1-logs" 2>/dev/null || true
    echo "  $h data wiped"
  fi
done

if [ "$REMOTE_CLEAN" = "true" ]; then
  echo "Cluster stopped, data wiped."
else
  echo "Cluster stopped, data preserved. Use --clean to wipe."
fi
