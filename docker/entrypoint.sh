#!/usr/bin/env sh
set -e

if [ -z "$CL_PASSWORD" ]; then
  echo "No password provided, using default password"
  export CL_PASSWORD=$CL_DEFAULT
fi

if [ -n "$CL_DAG_L1" ]; then

  # If no L0 peer ID is provided, we assume we're connecting to our own L0 validator
  if [ -z "$CL_L0_PEER_ID" ]; then
    echo "No L0 peer ID provided, generating new one"
    export CL_L0_PEER_ID=$(java -jar /tessellation/jars/wallet.jar show-id)
  fi

  # If CL_L0_PEER_HTTP_HOST is set to global-l0, replace it with the actual IP address
  if [ "$CL_L0_PEER_HTTP_HOST" = "global-l0" ]; then
    echo "Resolving global-l0 to IP address"
    export CL_L0_PEER_HTTP_HOST=$(getent hosts global-l0 | cut -d' ' -f1)
    echo "Using L0 peer HTTP host: $CL_L0_PEER_HTTP_HOST"
  fi

  echo "Starting L1 validator"
  exec java -jar /tessellation/jars/dag-l1.jar run-validator

elif [ -n "$CL_GENESIS_FILE" ]; then
  # if you’ve provided a genesis file, run genesis
  echo "Starting L0 validator genesis"
  exec java -jar /tessellation/jars/dag-l0.jar run-genesis "/tessellation/genesis.csv"

else
  # otherwise, default to L0 validator
  echo "Starting L0 validator"
  exec java -jar /tessellation/jars/dag-l0.jar run-validator

fi
