#!/bin/bash
set -e

if [ "$CL_DAG_L1_JOIN_ENABLED" = "true" ]; then
  export l1_host=$(getent hosts dag-l1 | cut -d' ' -f1)
  echo "L1 host: $l1_host"
  curl -X POST -H 'Content-Type: application/json' \
  -d "{\"id\":\"$CL_DAG_L1_JOIN_ID\",\"ip\":\"$CL_DAG_L1_JOIN_IP\",\"p2pPort\":$CL_DAG_L1_JOIN_PORT}" \
  http://$l1_host:${CL_DAG_L1_CLI_PORT}/cluster/join
fi

# Keep the container running
tail -f /dev/null