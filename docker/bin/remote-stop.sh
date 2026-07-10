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

# Save latest snapshot hash before stopping (for rollback restarts)
LAST_HASH=$(ssh "$GENESIS" "curl -sf http://localhost:9000/global-snapshots/latest 2>/dev/null" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['value']['lastSnapshotHash'])" 2>/dev/null || echo "")
if [ -n "$LAST_HASH" ]; then
  ssh "$GENESIS" "echo '$LAST_HASH' > $DIR/.last-snapshot-hash"
  echo "Saved snapshot hash for rollback: ${LAST_HASH:0:16}..."
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
