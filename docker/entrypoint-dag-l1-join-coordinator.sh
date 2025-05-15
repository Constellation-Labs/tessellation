#!/bin/bash

JOIN_RETRIES=10
JOIN_RETRY_DELAY=5

for i in $(seq 1 $JOIN_RETRIES); do
  if [ "$CL_DAG_L1_JOIN_ENABLED" = "true" ]; then
    echo "Joining cluster"
    curl -X POST -H 'Content-Type: application/json' \
    -d "{\"id\":\"$CL_DAG_L1_JOIN_ID\",\"ip\":\"$CL_DAG_L1_JOIN_IP\",\"p2pPort\":$CL_DAG_L1_JOIN_PORT}" \
      http://127.0.0.1:$L1_CL_CLI_HTTP_PORT/cluster/join || true
  fi
  sleep $JOIN_RETRY_DELAY
done
