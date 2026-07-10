#!/usr/bin/env bash
# Stop monitoring stack. Does NOT remove volumes (monitoring data is persistent).
# Expected env var: REMOTE_NODES

set -e

REMOTE_DIR="/opt/monitoring"

IFS=',' read -ra ALL_NODES <<< "$REMOTE_NODES"
SERVER_NODE="${ALL_NODES[3]:-${ALL_NODES[2]}}"

echo "Stopping monitoring on $SERVER_NODE"
ssh "$SERVER_NODE" "if [ -f $REMOTE_DIR/docker-compose.yaml ]; then cd $REMOTE_DIR && docker compose down 2>&1 | grep -E '(Stopped|Removed)' || true; fi"
echo "Monitoring stopped. Volumes preserved."
