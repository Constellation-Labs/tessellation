#!/usr/bin/env bash
set -e

echo "Environment before entrypoint:"
env

join_process() {
  JOIN_RETRIES=${CL_DOCKER_JOIN_RETRIES:-10}
  JOIN_RETRY_DELAY=${CL_DOCKER_JOIN_DELAY:-10}
  JOIN_INITIAL_DELAY=${CL_DOCKER_JOIN_INITIAL_DELAY:-30}
  # write payload to a temporary file
  payload_file="join-payload.json"
  cat > "$payload_file" <<EOF
{"id":"$CL_DOCKER_JOIN_ID","ip":"$CL_DOCKER_JOIN_IP","p2pPort":$CL_DOCKER_JOIN_PORT}
EOF
  if [ "$CL_DOCKER_JOIN" = "true" ]; then
    sleep $JOIN_INITIAL_DELAY;
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
        if [[ "$response" == "failure" ]]; then
          echo "Join failed, retrying..."
        elif [[ "$response" == *"does not allow for joining the cluster"* ]]; then
          echo "Join rejected (node not ready), retrying..."
        elif [[ "$response" == "" || "$response" == "\"\"" ]]; then
          echo "Join succeeded"
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

if [ "$ID" == "cl1" ]; then
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

echo "Using CL_GLOBAL_L0_PEER_ID: $CL_GLOBAL_L0_PEER_ID"
echo "Using L0 peer HTTP host: $CL_L0_PEER_HTTP_HOST"
echo "Using L0 peer HTTP port: $CL_L0_PEER_HTTP_PORT"
echo "Using L0 peer id: $CL_L0_PEER_ID"

export L0="false"
if [ "$ID" == "gl0" ] || [ "$ID" == "ml0" ]; then
  export L0="true"
fi

export RUN_COMMAND="run-validator"

# JVM tuning + Java 21 module access flags for Kryo serialization.
#
# Heap (-Xms2g -Xmx8g): matches RecommendedHeapMb=8192 in TessellationIOApp so the
# startup heap-check stays quiet. Without an explicit cap, Java 21's container
# support sizes max heap at 25% of host RAM -- on the 256 GB CI runner that gave
# each of 5 nodes ~30 GB, with G1 working sets to match. Smaller, fixed heap =
# shorter, more predictable mixed-mode collections, which matters because 5
# sibling JVMs hitting overlapping multi-second GC pauses is exactly the
# consensus-wedge profile observed in the 2026-05-05 fork-recovery flake.
#
# MaxGCPauseMillis=200: explicit pause-time goal for G1. Without this, G1 will
# tolerate longer pauses on a fat heap.
#
# Per-environment thread-pool caps (-XX:ActiveProcessorCount=N) are NOT set here;
# they belong in the compose file for the multi-node test scenario, where 5
# sibling JVMs share one box. Production validators run alone on dedicated
# hardware and should see all cores.
export CL_DOCKER_JAVA_OPTS="${CL_DOCKER_JAVA_OPTS:-} -Xms2g -Xmx8g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 --add-opens=java.base/java.lang.invoke=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.security=ALL-UNNAMED"

if [ "$CL_DOCKER_GENESIS" == "true" ]; then
  if [ "$L0" == "false" ]; then
    RUN_COMMAND="run-initial-validator"
  elif [ "$ID" == "ml0" ]; then
    if [ ! -f "/tessellation/data/snapshot/ordinal/0/0" ]; then
      RUN_COMMAND="run-genesis /tessellation/data/genesis.snapshot"
    elif [ "$CL_DOCKER_ROLLBACK" == "true" ]; then
      if [ -z "$CL_DOCKER_ROLLBACK_HASH" ]; then
        echo "Error: CL_DOCKER_ROLLBACK=true but CL_DOCKER_ROLLBACK_HASH is not set"
        echo "Please provide the snapshot hash to rollback to via --rollback-hash=<hash>"
        exit 1
      fi
      RUN_COMMAND="run-rollback $CL_DOCKER_ROLLBACK_HASH"
    else
      echo "Ordinal 0/0 exists. Use --rollback --rollback-hash=<hash> to restart from existing data"
      exit 1
    fi
  else
    if [ ! -f "/tessellation/data/snapshot/ordinal/0/0" ]; then
      RUN_COMMAND="run-genesis /tessellation/genesis.csv"
    elif [ "$CL_DOCKER_ROLLBACK" == "true" ]; then
      if [ -z "$CL_DOCKER_ROLLBACK_HASH" ]; then
        echo "Error: CL_DOCKER_ROLLBACK=true but CL_DOCKER_ROLLBACK_HASH is not set"
        echo "Please provide the snapshot hash to rollback to via --rollback-hash=<hash>"
        exit 1
      fi
      RUN_COMMAND="run-rollback $CL_DOCKER_ROLLBACK_HASH"
    else
      echo "Ordinal 0/0 exists. Use --rollback --rollback-hash=<hash> to restart from existing data"
      exit 1
    fi
  fi
fi

if [ -n "$CL_DOCKER_SEEDLIST" ]; then
  echo "Using seedlist: $CL_DOCKER_SEEDLIST"
  export RUN_COMMAND="$RUN_COMMAND --seedlist /tessellation/seedlist"
fi

if [ -n "$CL_DOCKER_PRIORITY_SEEDLIST" ]; then
  echo "Using priority seedlist: $CL_DOCKER_PRIORITY_SEEDLIST"
  export RUN_COMMAND="$RUN_COMMAND --prioritySeedlist /tessellation/priority-seedlist"
fi

export GENESIS_SNAPSHOT_ARG="";
export RUN_MAIN="true";

if [ "$ID" == "ml0" ] && [ "$CL_DOCKER_GENESIS" == "true" ] && [ -n "$CL_GENESIS_FILE" ]; then

  if [ -n "$CL_ML0_GENERATE_GENESIS" ]; then

    ml0_log_file="/tessellation/logs/ml0-create-genesis.log"
    touch $ml0_log_file
    java -jar /tessellation/jars/ml0.jar create-genesis /tessellation/genesis.csv 2>&1 | tee -a $ml0_log_file  # &
    echo "genesis.snapshot created"
    cp /tessellation/genesis.snapshot /tessellation/data/genesis.snapshot
    cp /tessellation/genesis.address /tessellation/data/genesis.address
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
  echo "Exit code: $exit_code"
  exit $exit_code
else 
  echo "Skipping run-main"
fi
