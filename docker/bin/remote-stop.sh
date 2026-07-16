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

# Stop snapshot-streaming first (if 4th node)
if [ -n "$SS_NODE" ]; then
  echo "Stopping snapshot-streaming on $SS_NODE"
  ssh "$SS_NODE" "if [ -f $DIR/snapshot-streaming/docker-compose.yaml ]; then cd $DIR/snapshot-streaming && docker compose down 2>&1 | grep -E '(Stopped|Removed)' || true; fi"
  if [ "$REMOTE_CLEAN" = "true" ]; then
    ssh "$SS_NODE" "rm -rf $DIR/snapshot-streaming/data && mkdir -p $DIR/snapshot-streaming/data; docker volume rm ss-pgdata block-explorer-node-modules 2>/dev/null" || true
    echo "  $SS_NODE data wiped"
  fi
fi

# Stop tx-sender on genesis node
GENESIS="${NODES[0]}"
ssh "$GENESIS" "docker rm -f tx-sender 2>/dev/null" || true

# Save the CURRENT head's rollback hash before stopping (chain-preserving restarts only).
# CRITICAL: never leave a STALE .last-snapshot-hash. If this fetch silently fails and an old
# value survives on disk, run-rollback rolls the WHOLE chain back to that ancient anchor
# (e.g. the original genesis/cutover ordinal) on the next deploy — discarding progress and
# breaking downstream consumers (snapshot-streaming re-seeds at the anchor and leaves a gap).
# So: retry the fetch, validate it, and on a data-preserving down ABORT if we cannot obtain a
# fresh hash rather than trusting whatever is already on disk.
if [ "$REMOTE_CLEAN" != "true" ]; then
  LAST_HASH=""
  for attempt in 1 2 3 4 5; do
    LAST_HASH=$(ssh "$GENESIS" "curl -sf --max-time 10 http://localhost:9000/global-snapshots/latest 2>/dev/null" \
      | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['value']['lastSnapshotHash'])" 2>/dev/null || echo "")
    if printf '%s' "$LAST_HASH" | grep -Eiq '^[0-9a-f]{64}$'; then break; fi
    LAST_HASH=""
    echo "  latest-snapshot fetch attempt ${attempt}/5 failed; retrying in 5s..." >&2
    sleep 5
  done
  if [ -n "$LAST_HASH" ]; then
    ssh "$GENESIS" "echo '$LAST_HASH' > $DIR/.last-snapshot-hash"
    echo "Saved snapshot hash for rollback: ${LAST_HASH:0:16}..."
  else
    echo "ERROR: could not fetch the current head snapshot hash from $GENESIS after 5 attempts." >&2
    echo "       Refusing to continue: a stale $DIR/.last-snapshot-hash would make the next" >&2
    echo "       run-rollback roll the chain back to an ancient anchor. Restore node reachability" >&2
    echo "       (or pass --clean for a fresh-genesis deploy) and retry." >&2
    exit 1
  fi
fi

for h in "${NODES[@]}"; do
  echo "Stopping $h"
  ssh "$h" "if [ -d $DIR ]; then cd $DIR && $COMPOSE --profile l0 --profile l1 down 2>&1 | grep -E '(Stopped|Removed)' || true; fi"
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
