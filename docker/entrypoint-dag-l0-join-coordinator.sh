#!/bin/bash


JOIN_RETRIES=${CL_DOCKER_L0_JOIN_RETRIES:-30}
JOIN_RETRY_DELAY=${CL_DOCKER_L0_JOIN_RETRY_DELAY:-5}


DELAY=${CL_DOCKER_L0_JOIN_DELAY:-60}

sleep $DELAY

for i in $(seq 1 $JOIN_RETRIES); do
  if [ "$CL_DAG_L0_JOIN_ENABLED" = "true" ]; then
    echo "Joining cluster (attempt $i)"
    echo "CL_DAG_L0_JOIN_ID: $CL_DAG_L0_JOIN_ID"
    echo "CL_DAG_L0_JOIN_IP: $CL_DAG_L0_JOIN_IP"
    echo "CL_DAG_L0_JOIN_PORT: $CL_DAG_L0_JOIN_PORT"

    # write payload to a temporary file
    payload_file="join-payload.json"
    cat > "$payload_file" <<EOF
{"id":"$CL_DAG_L0_JOIN_ID","ip":"$CL_DAG_L0_JOIN_IP","p2pPort":$CL_DAG_L0_JOIN_PORT}
EOF

    # show the payload
    echo "Payload:"
    cat "$payload_file"

    # send it via curl
    curl -X POST -H 'Content-Type: application/json' \
         --data @"$payload_file" \
         http://localhost:"$L0_CL_CLI_HTTP_PORT"/cluster/join || true

    # clean up
    rm -f "$payload_file"
  fi

  sleep $JOIN_RETRY_DELAY
done
