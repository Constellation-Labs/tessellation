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


if [ "$PURGE_CONFIG" = "true" ]; then
  rm -rf ./nodes
fi

for i in 0 1 2; do
  mkdir -p ./nodes/$i
done

source ./docker/bin/assembly.sh

export TESSELLATION_DOCKER_VERSION=test
docker build -t constellationnetwork/tessellation:$TESSELLATION_DOCKER_VERSION -f docker/Dockerfile .

source ./docker/bin/node-key-env-setup.sh
source ./docker/bin/docker-env-setup.sh

echo "------------------------------------------------"
echo "All deployment configurations now generated, proceeding to run cluster"
echo "------------------------------------------------"

# Wait for cleanup PID to finish
wait $CLEANUP_PID

docker network create \
  --driver=bridge \
  --subnet=${NET_PREFIX}.0/24 \
  tessellation_common


for i in 0 1 2; do
  cd ./nodes/$i/
  docker compose down --remove-orphans --volumes > /dev/null 2>&1 || true; \
  cp ../../docker/docker-compose.yaml . ; \
  cp ../../docker/docker-compose.test.yaml . ; \
  docker compose -f docker-compose.test.yaml -f docker-compose.yaml --profile l0 up -d
  cd ../../
done


show_time "Started docker compose"

echo "------------------------------------------------"
echo "Running end-to-end tests from .github/action_scripts"
echo "------------------------------------------------"

# Install dependencies
cd $PROJECT_ROOT/.github/action_scripts
echo "Installing Node.js dependencies..."
npm i @stardust-collective/dag4 js-sha256 axios brotli zod

source ../../docker/bin/health-check.sh
verify_healthy
show_time "Cluster became healthy"

cd $PROJECT_ROOT/.github/action_scripts/delegated_staking
node delegated-staking.js $DAG_L0_PORT_PREFIX $DAG_L1_PORT_PREFIX testDelegatedStaking

echo "------------------------------------------------"
echo "End-to-end tests completed"
echo "------------------------------------------------"

# Return to the original directory
cd "$SCRIPT_DIR/../../"
