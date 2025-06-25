#!/usr/bin/env bash
set -e


export START_TIME=$(date +%s)
export LATEST_TIME=$START_TIME

show_time() {
  local stage=$1
  export PREV_TIME=$LATEST_TIME
  export LATEST_TIME=$(date +%s)
  export DELTA_SECONDS_TOTAL=$((LATEST_TIME - START_TIME))
  export DELTA_SECONDS=$((LATEST_TIME - PREV_TIME))
  echo "$stage took: $DELTA_SECONDS seconds - total time: $DELTA_SECONDS_TOTAL seconds"
}


# Get the directory where this script is located
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cur_dir=$(pwd)
echo "Script started in $cur_dir with script directory $SCRIPT_DIR"

cd "$SCRIPT_DIR/../../"
cur_dir=$(pwd)
export PROJECT_ROOT=$cur_dir
echo "Running in top level directory $cur_dir"


source ./docker/bin/set-env.sh "$@"

./docker/bin/tessellation-docker-cleanup.sh & 
CLEANUP_PID=$!


source ./docker/bin/assembly.sh

export TESSELLATION_DOCKER_VERSION=test
docker build -t constellationnetwork/tessellation:$TESSELLATION_DOCKER_VERSION -f docker/Dockerfile .

# Wait for cleanup PID to finish
wait $CLEANUP_PID

if [ "$PURGE_CONFIG" = "true" ]; then
  echo "Purging config, removing $PROJECT_ROOT/nodes"
  sleep 1
  # strange issue with docker mount persistence, so we sleep and try twice, this may be removable now?
  rm -rf $PROJECT_ROOT/nodes
  sleep 1
  rm -rf $PROJECT_ROOT/nodes || true
  ls -la $PROJECT_ROOT/nodes || true
  echo "removed config, $PROJECT_ROOT/nodes"
fi

for i in 0 1 2; do
  mkdir -p ./nodes/$i
done

source ./docker/bin/node-key-env-setup.sh
source ./docker/bin/docker-env-setup.sh


echo "------------------------------------------------"
echo "All deployment configurations now generated, proceeding to run cluster"
echo "------------------------------------------------"


if [ "$BUILD_ONLY" = "true" ]; then
  echo "Build only mode, skipping container startup and end-to-end tests"
  exit 0
fi


docker network create \
  --driver=bridge \
  --subnet=${NET_PREFIX}.0/24 \
  tessellation_common

for i in 0 1 2; do
  cd ./nodes/$i/

  export PROFILE_GL0_ARG=""
  if [ "$i" -lt "$NUM_GL0_NODES" ]; then
    export PROFILE_GL0_ARG="--profile l0"
  fi

  export PROFILE_GL1_ARG=""
  if [ "$i" -lt "$NUM_GL1_NODES" ]; then
    export PROFILE_GL1_ARG="--profile l1"
  fi

  docker compose -f docker-compose.test.yaml \
  -f docker-compose.yaml \
  -f docker-compose.volumes.yaml \
  down --remove-orphans --volumes > /dev/null 2>&1 || true;

  cp ../../docker/docker-compose.yaml . ; \
  cp ../../docker/docker-compose.test.yaml . ; \
  cp ../../docker/docker-compose.volumes.yaml . ; \
  cp ../../docker/docker-compose.metagraph.yaml . ;
  cp ../../docker/docker-compose.metagraph-test.yaml . ;
  cp ../../docker/docker-compose.metagraph-genesis.yaml . ;

  docker_additional_args="$PROFILE_GL0_ARG $PROFILE_GL1_ARG"
  echo "docker_additional_args: $docker_additional_args"
  
  docker compose -f docker-compose.test.yaml \
  -f docker-compose.yaml \
  -f docker-compose.volumes.yaml \
  $docker_additional_args \
  up -d
  cd ../../
done

for i in 0 1 2; do
  cd ./nodes/$i/

  
  export PROFILE_ML0_ARG=""
  if [ "$i" -lt "$NUM_ML0_NODES" ]; then
    export PROFILE_ML0_ARG="--profile ml0"
  fi

  export PROFILE_CL1_ARG=""
  if [ "$i" -lt "$NUM_CL1_NODES" ]; then
    export PROFILE_CL1_ARG="--profile cl1"
  fi

  export PROFILE_DL1_ARG=""
  if [ "$i" -lt "$NUM_DL1_NODES" ]; then
    export PROFILE_DL1_ARG="--profile dl1"
  fi

  metagraph_args=""

  if [ -n "$METAGRAPH" ]; then
    metagraph_profile_args="$PROFILE_ML0_ARG $PROFILE_CL1_ARG $PROFILE_DL1_ARG"
    metagraph_args="-f docker-compose.metagraph.yaml -f docker-compose.metagraph-test.yaml"
    echo "Setting metagraph args to $metagraph_args"
    if [ ! -f "./genesis.snapshot" ] && [ "$i" -eq 0 ]; then
      echo "Generating metagraph genesis snapshot"
      cp .env .env.bak
      echo "CL_ML0_GENERATE_GENESIS=true" >> .env
      docker compose $metagraph_args -f docker-compose.metagraph-genesis.yaml --profile ml0 up
      docker stop ml0-0
      docker rm ml0-0
      cp ml0-data/genesis.snapshot .
      cp ml0-data/genesis.address .
      mv .env.bak .env
      export METAGRAPH_ID=$(head -n 1 genesis.address)
    fi
    echo "METAGRAPH_ID=$METAGRAPH_ID" >> .env
    echo "CL_L0_TOKEN_IDENTIFIER=$METAGRAPH_ID" >> .env

    # this can enabled as a double check after explicitly preventing it from picking up the main compose file, for now rely on initial cleanup
    # docker compose $metagraph_args down --remove-orphans --volumes > /dev/null 2>&1 || true;

    docker_additional_args="$metagraph_args $metagraph_profile_args"
    echo "docker_additional_args: $docker_additional_args"
    
    docker compose \
    $docker_additional_args \
    up -d
  fi

  cd ../../
done


show_time "Started docker compose"



if [ "$DOCKER_UP" = "true" ]; then
  echo "Docker up mode, skipping end-to-end tests"
  exit 0
fi


echo "------------------------------------------------"
echo "Running end-to-end tests from .github/action_scripts"
echo "------------------------------------------------"

# Install dependencies
cd $PROJECT_ROOT/.github/action_scripts
echo "Installing Node.js dependencies..."
npm i @stardust-collective/dag4 js-sha256 axios brotli zod

sleep 10

docker logs gl0-0

echo "GL0-0 logs above, now continuing with cluster health check."

source ../../docker/bin/cluster-health-check.sh
verify_healthy
show_time "Cluster became healthy"

cd $PROJECT_ROOT/.github/action_scripts/delegated_staking
node delegated-staking.js $DAG_L0_PORT_PREFIX $DAG_L1_PORT_PREFIX testDelegatedStaking

echo "------------------------------------------------"
echo "End-to-end tests completed"
echo "------------------------------------------------"

cd $PROJECT_ROOT


# TODO: Use a trap function
if [ "$CLEANUP_DOCKER_AT_END" == "true" ]; then
  ./docker/bin/tessellation-docker-cleanup.sh
fi



