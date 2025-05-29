#!/usr/bin/env sh
set -e

if [ -z "$CL_PASSWORD" ]; then
  echo "No password provided, using default password"
  export CL_PASSWORD=$CL_DEFAULT
fi

if [ -n "$CL_DAG_L1" ]; then

  # Only for tests
  if [ -z "$CL_EXTERNAL_IP" ]; then
    export CL_EXTERNAL_IP=${NET_PREFIX}.2${CONTAINER_OFFSET:-0}
    echo "Using external IP for DAG L1: $CL_EXTERNAL_IP"
  fi

  # If no L0 peer ID is provided, we assume we're connecting to our own L0 validator
  if [ -z "$CL_L0_PEER_ID" ]; then
    echo "No L0 peer ID provided, generating new one"
    export CL_L0_PEER_ID=$(java -jar /tessellation/jars/wallet.jar show-id)
  fi

  # If CL_L0_PEER_HTTP_HOST is set to gl0, replace it with the actual IP address
  if [ "$CL_L0_PEER_HTTP_HOST" = "gl0" ]; then
    echo "Resolving gl0 to IP address"
    export CL_L0_PEER_HTTP_HOST=$(getent hosts gl0 | cut -d' ' -f1)
    echo "Using L0 peer HTTP host: $CL_L0_PEER_HTTP_HOST"
  fi

  echo "Starting L1 validator"
  echo "Using L0 peer HTTP host: $CL_L0_PEER_HTTP_HOST"
  # Start the join coordinator in the background
  echo "Starting join coordinator"
  /tessellation/entrypoint-gl1-join-coordinator.sh &
  L1_COMMAND="run-validator"
  if [ -n "$CL_GENESIS_FILE" ]; then
    L1_COMMAND="run-initial-validator"
  fi
  exec java $CL_DOCKER_JAVA_OPTS -jar /tessellation/jars/gl1.jar $L1_COMMAND --l0-peer-host $CL_L0_PEER_HTTP_HOST
else
  echo "Starting L0 validator"
  
  # Only for tests
  if [ -z "$CL_EXTERNAL_IP" ]; then
    export CL_EXTERNAL_IP=${NET_PREFIX}.1${CONTAINER_OFFSET:-0}
    echo "Using external IP for gl0: $CL_EXTERNAL_IP"
  fi

  if [ -n "$CL_GENESIS_FILE" ]; then
    # if you’ve provided a genesis file, run genesis
    echo "Starting L0 validator genesis"
    exec java $CL_DOCKER_JAVA_OPTS -jar /tessellation/jars/dag-l0.jar run-genesis "/tessellation/genesis.csv"
  else
    echo "Starting join coordinator"
    /tessellation/entrypoint-dag-l0-join-coordinator.sh &
    # otherwise, default to L0 validator
    export L0_COMMAND="run-validator"
    if [ -n "$L0_COMMAND" ]; then
      export L0_COMMAND="$L0_COMMAND"
    fi

    if [ -n "$CL_DOCKER_SEEDLIST" ]; then
      echo "Using seedlist: $CL_DOCKER_SEEDLIST"
      export L0_COMMAND="$L0_COMMAND --seedlist /tessellation/seedlist"
    fi

    echo "Starting L0 validator with command: $L0_COMMAND"
    exec java $CL_DOCKER_JAVA_OPTS -jar /tessellation/jars/dag-l0.jar $L0_COMMAND
  fi

fi
