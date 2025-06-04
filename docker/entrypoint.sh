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
        response=$(curl -X POST -H 'Content-Type: application/json' \
             --data @"$payload_file" \
             http://localhost:"$CL_DOCKER_JOIN_CLI_PORT"/cluster/join || echo "failure")
        echo "Join response: $response"
        if [ "$response" == "failure" ]; then
          echo "Join failed, retrying..."
        elif [ "$response" == *"does not allow for joining the cluster"* ]; then
          echo "Join completed"
          break
        else
          echo "Join not obvious failure, retrying..."
        fi
      sleep "$JOIN_RETRY_DELAY"
    done
  fi
  echo "Join complete"
}

join_process &

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

echo "Using external IP $CL_EXTERNAL_IP for service $ID"

if [ -z "$CL_L0_PEER_ID" ]; then
  echo "No L0 peer ID provided, assume we're connecting to our own L0 validator, generating id from jar"
  export CL_L0_PEER_ID=$(java -jar /tessellation/jars/wallet.jar show-id)
fi

if [ -z "$CL_GLOBAL_L0_PEER_ID" ]; then
  echo "No CL_GLOBAL_L0_PEER_ID peer ID provided, assume we're connecting to our own L0 validator, generating id from jar"
  export CL_GLOBAL_L0_PEER_ID=$(java -jar /tessellation/jars/wallet.jar show-id)
fi

echo "Using L0 peer HTTP host: $CL_L0_PEER_HTTP_HOST"
echo "Using L0 peer HTTP port: $CL_L0_PEER_HTTP_PORT"
echo "Using L0 peer id: $CL_L0_PEER_ID"

export L0="false"
if [ "$ID" == "gl0" ] || [ "$ID" == "ml0" ]; then
  export L0="true"
fi

export RUN_COMMAND="run-validator"

if [ "$CL_DOCKER_GENESIS" == "true" ]; then
  if [ "$L0" == "false" ]; then
    RUN_COMMAND="run-initial-validator"
  elif [ "$ID" == "ml0" ]; then
    RUN_COMMAND="run-genesis /tessellation/data/genesis.snapshot"
  else
    RUN_COMMAND="run-genesis /tessellation/genesis.csv"
  fi
fi

if [ -n "$CL_DOCKER_SEEDLIST" ]; then
  echo "Using seedlist: $CL_DOCKER_SEEDLIST"
  export RUN_COMMAND="$RUN_COMMAND --seedlist /tessellation/seedlist"
fi

export GENESIS_SNAPSHOT_ARG="";
export RUN_MAIN="true";

if [ "$ID" == "ml0" ] && [ "$CL_DOCKER_GENESIS" == "true" ] && [ -n "$CL_GENESIS_FILE" ]; then
  export RUN_COMMAND="$RUN_COMMAND /tessellation/data/genesis.snapshot"

  if [ -n "$CL_ML0_GENERATE_GENESIS" ]; then

    ml0_log_file="/tessellation/logs/ml0-create-genesis.log"
    touch $ml0_log_file
    java -jar /tessellation/jars/ml0.jar create-genesis /tessellation/genesis.csv 2>&1 | tee -a $ml0_log_file  # &
    # CREATE_GENESIS_PID=$!

    # # Wait for genesis.snapshot to be created
    # MAX_WAIT_TIME=60 
    # elapsed_time=0
    # while [ ! -f "/tessellation/genesis.snapshot" ]; do
    #   sleep 1
    #   elapsed_time=$((elapsed_time + 1))
    #   if [ "$elapsed_time" -ge "$MAX_WAIT_TIME" ]; then
    #     echo "Error: genesis.snapshot was not created within $MAX_WAIT_TIME seconds."
    #     exit 1
    #   fi
    # done
    echo "genesis.snapshot created"
    cp /tessellation/genesis.snapshot /tessellation/data/genesis.snapshot
    cp /tessellation/genesis.address /tessellation/data/genesis.address
    # kill -9 $CREATE_GENESIS_PID
    export RUN_MAIN="false"
  fi
fi

export JAR_PATH="/tessellation/jars/$ID.jar"

echo "JAR_PATH: $JAR_PATH"

if [ ! -f "$JAR_PATH" ]; then
  echo "Error: $JAR_PATH does not exist"
  exit 1
fi

if [ "$RUN_MAIN" == "true" ]; then
  echo "Running $RUN_COMMAND"
  RUN_LOG_FILE="/tessellation/logs/$ID-run.log"
  echo "Running command   java $CL_DOCKER_JAVA_OPTS -jar "$JAR_PATH" $RUN_COMMAND 2>&1 | tee -a $RUN_LOG_FILE "
  java $CL_DOCKER_JAVA_OPTS -jar "$JAR_PATH" $RUN_COMMAND 2>&1 | tee -a $RUN_LOG_FILE
  # Capture Java’s exit code (PIPESTATUS[0] is Java; [1] would be tee)
  exit_code=${PIPESTATUS[0]}
  exit $exit_code
  # > $RUN_LOG_FILE 2>&1
  # exec java $CL_DOCKER_JAVA_OPTS -jar "$JAR_PATH" $RUN_COMMAND
else 
  echo "Skipping run-main"
fi
