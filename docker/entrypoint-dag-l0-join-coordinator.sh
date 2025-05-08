#!/bin/bash
set -e

sleep 30

if [ "$CL_DAG_L0_JOIN_ENABLED" = "true" ]; then
  echo "Joining cluster"
  echo "CL_DAG_L0_JOIN_ID: $CL_DAG_L0_JOIN_ID"
  echo "CL_DAG_L0_JOIN_IP: $CL_DAG_L0_JOIN_IP"
  echo "CL_DAG_L0_JOIN_PORT: $CL_DAG_L0_JOIN_PORT"
  curl -X POST -H 'Content-Type: application/json' \
  -d "{\"id\":\"$CL_DAG_L0_JOIN_ID\",\"ip\":\"$CL_DAG_L0_JOIN_IP\",\"p2pPort\":$CL_DAG_L0_JOIN_PORT}" \
  http://127.0.0.1:9002/cluster/join
fi

# Keep the container running
tail -f /dev/null