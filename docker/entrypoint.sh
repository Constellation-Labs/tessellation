#!/usr/bin/env sh
set -e

if [ -n "$CL_GENESIS_FILE" ]; then
  # if you’ve provided a genesis file, run genesis
  exec java -jar /tessellation/jars/dag-l0.jar run-genesis "$CL_GENESIS_FILE"

elif [ -n "$CL_DAG_L1" ]; then
  # if you’ve provided an L0 peer host, spin up the L1 validator

  # If no L0 peer ID is provided, we assume we're connecting to our own L0 validator
  if [ -z "$CL_L0_PEER_ID" ]; then
    export CL_L0_PEER_ID=$(java -jar /tessellation/jars/wallet.jar show-id)
  fi

  exec java -jar /tessellation/jars/dag-l1.jar run-validator

else
  # otherwise, default to L0 validator
  exec java -jar /tessellation/jars/dag-l0.jar run-validator

fi
