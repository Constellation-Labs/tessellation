#!/usr/bin/env bash
set -e

join_process() {
  JOIN_RETRIES=${CL_DOCKER_JOIN_RETRIES:-10}
  JOIN_RETRY_DELAY=${CL_DOCKER_JOIN_DELAY:-10}
  DELAY=${CL_DOCKER_JOIN_DELAY_INITIAL:-30}
  # write payload to a temporary file
  payload_file="join-payload.json"
  cat > "$payload_file" <<EOF
{"id":"$CL_DOCKER_JOIN_ID","ip":"$CL_DOCKER_JOIN_IP","p2pPort":$CL_DOCKER_JOIN_PORT}
EOF
  if [ "$CL_DOCKER_JOIN" = "true" ]; then
    sleep $DELAY;
    for i in $(seq 1 $JOIN_RETRIES); do
        echo "Joining cluster (attempt $i)"
        echo "Join id: $CL_DOCKER_JOIN_ID"
        echo "Join ip: $CL_DOCKER_JOIN_IP"
        echo "Join port: $CL_DOCKER_JOIN_PORT"
        # show the payload
        echo "Payload:"
        cat "$payload_file"
        # send it via curl
        curl -X POST -H 'Content-Type: application/json' \
             --data @"$payload_file" \
             http://localhost:"$CL_DOCKER_INTERNAL_L0_CLI"/cluster/join || true
      sleep "$JOIN_RETRY_DELAY"
    done
  fi
  echo "Join complete"
}

if [ -z "$CL_PASSWORD" ]; then
  echo "No password provided, using default password"
  export CL_PASSWORD=$CL_DEFAULT
fi

ID=$CL_DOCKER_ID

if [ "$ID" == "gl0" ]; then
  export CL_DOCKER_TEST_NETWORK_SUFFIX=1;
fi

if [ "$ID" == "gl1" ]; then
  export CL_DOCKER_TEST_NETWORK_SUFFIX=2;
fi

if [ "$ID" == "ml0" ]; then
  export CL_DOCKER_TEST_NETWORK_SUFFIX=3;
fi

if [ "$ID" == "ml1" ]; then
  export CL_DOCKER_TEST_NETWORK_SUFFIX=4;
fi

if [ "$ID" == "dl1" ]; then
  export CL_DOCKER_TEST_NETWORK_SUFFIX=5;
fi

if [ -z "$CL_EXTERNAL_IP" ]; then
  export CL_EXTERNAL_IP=${NET_PREFIX}.${CL_DOCKER_TEST_NETWORK_SUFFIX:-1}${CONTAINER_OFFSET:-0}
fi

echo "Using external IP for DAG L1: $CL_EXTERNAL_IP"

if [ -z "$CL_L0_PEER_ID" ]; then
  echo "No L0 peer ID provided, assume we're connecting to our own L0 validator, generating id from jar"
  export CL_L0_PEER_ID=$(java -jar /tessellation/jars/wallet.jar show-id)
fi

if [ -z "$CL_GLOBAL_L0_PEER_ID" ]; then
  echo "No CL_GLOBAL_L0_PEER_ID peer ID provided, assume we're connecting to our own L0 validator, generating id from jar"
  export $CL_GLOBAL_L0_PEER_ID=$(java -jar /tessellation/jars/wallet.jar show-id)
fi

echo "Using L0 peer HTTP host: $CL_L0_PEER_HOST"
echo "Using L0 peer HTTP port: $CL_L0_PEER_PORT"
echo "Using L0 peer id: $CL_L0_PEER_ID"

export L0="false"
if [ "$ID" == "gl0" ] || [ "$ID" == "ml0" ]; then
  export L0="true"
fi

export RUN_COMMAND="run-validator"

if [ "$CL_DOCKER_GENESIS" == "true" ]; then
  if [ "$L0" == "false" ]; then
    RUN_COMMAND="run-initial-validator"
  else
    RUN_COMMAND="run-genesis /tessellation/genesis.csv"
  fi
fi

if [ -n "$CL_DOCKER_SEEDLIST" ]; then
  echo "Using seedlist: $CL_DOCKER_SEEDLIST"
  export RUN_COMMAND="$RUN_COMMAND --seedlist /tessellation/seedlist"
fi

exec java "$CL_DOCKER_JAVA_OPTS" -jar "/tessellation/jars/$ID.jar" $RUN_COMMAND
