#!/bin/bash

JOIN_RETRIES=20
JOIN_RETRY_DELAY=5

if [ -z "$CL_DOCKER_L1_JOIN_DELAY" ]; then
  export CL_DOCKER_L1_JOIN_DELAY=0
fi

echo "Joining cluster with delay of $CL_DOCKER_L1_JOIN_DELAY seconds"
sleep $CL_DOCKER_L1_JOIN_DELAY
echo "Joining cluster started after delay of $CL_DOCKER_L1_JOIN_DELAY seconds"


for i in $(seq 1 $JOIN_RETRIES); do
  if [ "$CL_DAG_L1_JOIN_ENABLED" = "true" ]; then
    echo "Joining cluster"
    result=$(curl -X POST -H 'Content-Type: application/json' \
    -d "{\"id\":\"$CL_DAG_L1_JOIN_ID\",\"ip\":\"$CL_DAG_L1_JOIN_IP\",\"p2pPort\":$CL_DAG_L1_JOIN_PORT}" \
      http://127.0.0.1:$L1_CL_CLI_HTTP_PORT/cluster/join || echo "null")
    echo "join result: $result"
    # if [[ "$result" != "null" ]] && [[ "$result" != *"state=Initial"* ]]; then
    #   echo "Joined successfully, state is no longer Initial."
    #   break
    # fi
  fi
  sleep $JOIN_RETRY_DELAY
done
