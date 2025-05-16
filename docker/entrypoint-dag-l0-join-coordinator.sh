#!/bin/bash


JOIN_RETRIES=10
JOIN_RETRY_DELAY=5

for i in $(seq 1 $JOIN_RETRIES); do
  if [ "$CL_DAG_L0_JOIN_ENABLED" = "true" ]; then
    echo "Joining cluster"
    echo "CL_DAG_L0_JOIN_ID: $CL_DAG_L0_JOIN_ID"
    echo "CL_DAG_L0_JOIN_IP: $CL_DAG_L0_JOIN_IP"
    echo "CL_DAG_L0_JOIN_PORT: $CL_DAG_L0_JOIN_PORT"
    curl -X POST -H 'Content-Type: application/json' \
    -d "{\"id\":\"$CL_DAG_L0_JOIN_ID\",\"ip\":\"$CL_DAG_L0_JOIN_IP\",\"p2pPort\":$CL_DAG_L0_JOIN_PORT}" \
    http://127.0.0.1:$L0_CL_CLI_HTTP_PORT/cluster/join || true
  fi
  sleep $JOIN_RETRY_DELAY
done
