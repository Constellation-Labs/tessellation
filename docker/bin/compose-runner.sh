#!/usr/bin/env bash

set -e 

if [ "$BASH_DEBUG_MODE" = "true" ]; then
  set -x             # Print each command before executing (verbose)
  set -eo pipefail  # Exit on error, pipe failures
fi


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

cleanup_end() {
  if [ "$CLEANUP_DOCKER_AT_END" == "true" ] && { [ -z "$TEST_HOST" ] || [ "$TEST_HOST" = "http://localhost" ]; }; then
    ./docker/bin/tessellation-docker-cleanup.sh
  fi
}

trap cleanup_end EXIT

# Get the directory where this script is located
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cur_dir=$(pwd)
echo "Script started in $cur_dir with script directory $SCRIPT_DIR"

cd "$SCRIPT_DIR/../../"
cur_dir=$(pwd)
export PROJECT_ROOT=$cur_dir
echo "Running in top level directory $cur_dir"


source ./docker/bin/set-env.sh "$@"

if [ "$LIST_TESTS" = "true" ]; then
  echo "================================================"
  echo "Available tests:"
  echo "================================================"
  echo ""
  echo "DAG tests (no metagraph required):"
  echo "  dag-cluster              DAG cluster check"
  echo "  delegated-staking        Delegated staking tests"
  echo "  token-lock-replacement   Token lock replacement edge case tests"
  echo ""
  echo "Metagraph tests (require --use-test-metagraph):"
  echo "  currency                 Metagraph currency transaction tests"
  echo "  rewards                  Metagraph rewards tests"
  echo "  token-locks              Token lock tests"
  echo "  allow-spends             Allow-spend tests"
  echo "  spend                    Spend transaction tests"
  echo "  data-without-fee         Data transaction tests (without fee, requires CI_PRIVATE_KEY)"
  echo "  data-with-fee            Data transaction tests (with fee, requires CI_PRIVATE_KEY)"
  echo ""
  echo "Usage: just test --test=dag-cluster --test=delegated-staking"
  echo "       just test --test=dag-cluster,rewards    (comma-separated)"
  exit 0
fi

# If tests are selected, check if any require metagraph. If not, skip metagraph setup.
METAGRAPH_TESTS="currency,rewards,token-locks,allow-spends,spend,data-without-fee,data-with-fee"
if [ -n "$SELECTED_TESTS" ] && [ -n "$METAGRAPH" ]; then
  needs_metagraph=false
  for t in $(echo "$SELECTED_TESTS" | tr ',' ' '); do
    if echo "$METAGRAPH_TESTS" | tr ',' '\n' | grep -qx "$t"; then
      needs_metagraph=true
      break
    fi
  done
  if [ "$needs_metagraph" = "false" ]; then
    echo "Selected tests do not require metagraph, skipping metagraph setup"
    unset METAGRAPH
    export NUM_ML0_NODES=0
    export NUM_CL1_NODES=0
    export NUM_DL1_NODES=0
  fi
fi

REMOTE_HOST=${TEST_HOST:-}

if [ -n "$REMOTE_HOST" ] && [ "$REMOTE_HOST" != "http://localhost" ]; then
  echo "------------------------------------------------"
  echo "Remote host provided ($REMOTE_HOST), skipping docker setup"
  echo "------------------------------------------------"
else
  ./docker/bin/tessellation-docker-cleanup.sh &
  CLEANUP_PID=$!

  echo "Starting assembly"
  source ./docker/bin/assembly.sh

  export TESSELLATION_DOCKER_VERSION=test

  echo "Finished assembly, building docker image"
  docker build -t constellationnetwork/tessellation:$TESSELLATION_DOCKER_VERSION -f docker/Dockerfile .


  # Wait for cleanup PID to finish
  wait $CLEANUP_PID

  if [ "$PURGE_CONFIG" = "true" ]; then
    echo "Purging config, removing $PROJECT_ROOT/nodes"
    sleep 1
    # strange issue with docker mount persistence, so we sleep and try twice, this may be removable now?
    ./docker/bin/clean-configs.sh
    sleep 1
    ./docker/bin/clean-configs.sh
    ls -la $PROJECT_ROOT/nodes || true
    echo "removed config, $PROJECT_ROOT/nodes"
  fi

  for i in $(seq 0 $((MAX_NODES - 1))); do
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

  # Phase 1: Setup compose files and start GL0 nodes
  for i in $(seq 0 $((MAX_NODES - 1))); do
    cd ./nodes/$i/

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

    if [ "$i" -lt "$NUM_GL0_NODES" ]; then
      docker compose -f docker-compose.test.yaml \
      -f docker-compose.yaml \
      -f docker-compose.volumes.yaml \
      --profile l0 \
      up -d
    fi

    cd ../../
  done

  # Wait for GL0 cluster to be ready before starting GL1
  # GL1 needs GL0 for L0PeerDiscovery; without this, GL1's join state machine
  # gets stuck at SessionStarted.
  if [ "$NUM_GL0_NODES" -gt 0 ] && [ "$NUM_GL1_NODES" -gt 0 ]; then
    echo "Waiting for GL0 cluster to be ready before starting GL1..."
    gl0_url="${TEST_HOST:-http://localhost}:${DAG_L0_PORT_PREFIX}00"
    gl0_ready=false
    for attempt in $(seq 1 60); do
      cluster_info=$(curl -s "${gl0_url}/cluster/info" 2>/dev/null || echo "")
      if [ -n "$cluster_info" ] && echo "$cluster_info" | jq 'length' >/dev/null 2>&1; then
        node_count=$(echo "$cluster_info" | jq 'length')
        if [ "$node_count" -ge 1 ]; then
          echo "GL0 cluster ready with $node_count node(s)"
          gl0_ready=true
          break
        fi
      fi
      echo "GL0 not ready yet (attempt $attempt/60), waiting..."
      sleep 5
    done
    if [ "$gl0_ready" = "false" ]; then
      echo "ERROR: GL0 did not become ready in time"
      docker logs gl0-0 || true
      exit 1
    fi
  fi

  # Phase 2: Start GL1 nodes (GL0 is now ready for peer discovery)
  for i in $(seq 0 $((MAX_NODES - 1))); do
    cd ./nodes/$i/

    if [ "$i" -lt "$NUM_GL1_NODES" ]; then
      docker compose -f docker-compose.test.yaml \
      -f docker-compose.yaml \
      -f docker-compose.volumes.yaml \
      --profile l1 \
      up -d
    fi

    cd ../../
  done

  # Wait for GL0 to be ready before starting metagraph nodes
  if [ -n "$METAGRAPH" ] && [ "$NUM_GL0_NODES" -gt 0 ]; then
    echo "Waiting for GL0 to be ready before starting metagraph..."
    gl0_url="${TEST_HOST:-http://localhost}:${DAG_L0_PORT_PREFIX}00"
    gl0_ready=false
    for attempt in $(seq 1 60); do
      cluster_info=$(curl -s "${gl0_url}/cluster/info" 2>/dev/null || echo "")
      if [ -n "$cluster_info" ] && echo "$cluster_info" | jq 'length' >/dev/null 2>&1; then
        node_count=$(echo "$cluster_info" | jq 'length')
        if [ "$node_count" -ge 1 ]; then
          echo "GL0 is ready with $node_count node(s)"
          gl0_ready=true
          break
        fi
      fi
      echo "GL0 not ready yet (attempt $attempt/60), waiting..."
      sleep 5
    done
    if [ "$gl0_ready" = "false" ]; then
      echo "ERROR: GL0 did not become ready in time"
      docker logs gl0-0 || true
      exit 1
    fi
  fi

  if [ -n "$METAGRAPH" ]; then
    metagraph_args="-f docker-compose.metagraph.yaml -f docker-compose.metagraph-test.yaml"

    # Phase 1: Genesis creation, set METAGRAPH_ID, and start ML0
    for i in $(seq 0 $((MAX_NODES - 1))); do
      cd ./nodes/$i/

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

      if [ "$i" -lt "$NUM_ML0_NODES" ]; then
        echo "Starting ML0 for node $i"
        docker compose $metagraph_args --profile ml0 up -d
      fi

      cd ../../
    done

    # Wait for ML0 to be ready before starting metagraph L1 layers (CL1/DL1)
    if [ "$NUM_ML0_NODES" -gt 0 ]; then
      echo "Waiting for ML0 to be ready before starting metagraph L1 layers..."
      ml0_url="${TEST_HOST:-http://localhost}:${ML0_PORT_PREFIX}00"
      ml0_ready=false
      for attempt in $(seq 1 60); do
        cluster_info=$(curl -s "${ml0_url}/cluster/info" 2>/dev/null || echo "")
        if [ -n "$cluster_info" ] && echo "$cluster_info" | jq 'length' >/dev/null 2>&1; then
          node_count=$(echo "$cluster_info" | jq 'length')
          if [ "$node_count" -ge 1 ]; then
            echo "ML0 is ready with $node_count node(s)"
            ml0_ready=true
            break
          fi
        fi
        echo "ML0 not ready yet (attempt $attempt/60), waiting..."
        sleep 5
      done
      if [ "$ml0_ready" = "false" ]; then
        echo "ERROR: ML0 did not become ready in time"
        docker logs ml0-0 || true
        exit 1
      fi
    fi

    # Phase 2: Start CL1/DL1 services (ML0 is now ready)
    for i in $(seq 0 $((MAX_NODES - 1))); do
      cd ./nodes/$i/

      l1_profile_args=""
      if [ "$i" -lt "$NUM_CL1_NODES" ]; then
        l1_profile_args="$l1_profile_args --profile cl1"
      fi
      if [ "$i" -lt "$NUM_DL1_NODES" ]; then
        l1_profile_args="$l1_profile_args --profile dl1"
      fi
      l1_profile_args=$(echo $l1_profile_args | xargs)

      if [ -n "$l1_profile_args" ]; then
        echo "Starting CL1/DL1 for node $i"
        docker compose $metagraph_args $l1_profile_args up -d
      fi

      cd ../../
    done
  fi


  show_time "Started docker compose"



  if [ "$DOCKER_UP" = "true" ]; then
    echo "Docker up mode, skipping end-to-end tests"
    exit 0
  fi
fi


# ------------------------------------------------
# Test selection helpers
# ------------------------------------------------
should_run_test() {
  local test_name=$1
  if [ -z "$SELECTED_TESTS" ]; then
    return 0
  fi
  echo "$SELECTED_TESTS" | tr ',' '\n' | grep -qx "$test_name"
}

if [ -n "$SELECTED_TESTS" ]; then
  echo "------------------------------------------------"
  echo "Running selected tests: $SELECTED_TESTS"
  echo "------------------------------------------------"
fi

echo "------------------------------------------------"
echo "Running end-to-end tests from .github/action_scripts"
echo "------------------------------------------------"

# Install dependencies
cd $PROJECT_ROOT/.github/action_scripts
echo "Installing Node.js dependencies..."
npm i @stardust-collective/dag4 js-sha256 axios brotli zod elliptic

if [ -z "$REMOTE_HOST" ] || [ "$REMOTE_HOST" = "http://localhost" ]; then
  sleep 10
  docker logs gl0-0
  echo "GL0-0 logs above, now continuing with cluster health check."
fi

source ../../docker/bin/cluster-health-check.sh
verify_healthy
show_time "Cluster became healthy"

# ------------------------------------------------
# GL0/GL1 tests (no metagraph required)
# ------------------------------------------------

if should_run_test "dag-cluster"; then
  echo "================================================"
  echo "Running DAG cluster check"
  echo "================================================"
  cd $PROJECT_ROOT/.github/action_scripts/check_clusters
  node dag.js $DAG_L0_PORT_PREFIX $DAG_L1_PORT_PREFIX $ML0_PORT_PREFIX $CL1_PORT_PREFIX $DL1_PORT_PREFIX
  show_time "DAG cluster check completed"
fi

if should_run_test "delegated-staking"; then
  echo "================================================"
  echo "Running delegated staking tests"
  echo "================================================"
  cd $PROJECT_ROOT/.github/action_scripts/delegated_staking
  node delegated-staking.js $DAG_L0_PORT_PREFIX $DAG_L1_PORT_PREFIX testDelegatedStaking
  show_time "Delegated staking tests completed"
fi

if should_run_test "token-lock-replacement"; then
  echo "================================================"
  echo "Running token lock replacement edge case tests"
  echo "================================================"
  cd $PROJECT_ROOT/.github/action_scripts/delegated_staking
  node token-lock-replacement-edge-cases.js $DAG_L0_PORT_PREFIX $DAG_L1_PORT_PREFIX testTokenLockReplacementEdgeCases
  show_time "Token lock replacement edge case tests completed"
fi

# ------------------------------------------------
# Metagraph tests (require --use-test-metagraph or --metagraph=...)
# ------------------------------------------------

if [ -n "$METAGRAPH" ]; then

  if should_run_test "currency"; then
    echo "================================================"
    echo "Running metagraph currency transaction tests"
    echo "================================================"
    cd $PROJECT_ROOT/.github/action_scripts
    node send_transactions/currency.js $DAG_L0_PORT_PREFIX $DAG_L1_PORT_PREFIX $ML0_PORT_PREFIX $CL1_PORT_PREFIX $DL1_PORT_PREFIX
    show_time "Currency transaction tests completed"
  fi

  if should_run_test "rewards"; then
    echo "================================================"
    echo "Running metagraph rewards tests"
    echo "================================================"
    cd $PROJECT_ROOT/.github/action_scripts
    node rewards.js $DAG_L0_PORT_PREFIX $DAG_L1_PORT_PREFIX $ML0_PORT_PREFIX $CL1_PORT_PREFIX $DL1_PORT_PREFIX
    show_time "Metagraph rewards tests completed"
  fi

  if should_run_test "token-locks"; then
    echo "================================================"
    echo "Running token lock tests"
    echo "================================================"
    cd $PROJECT_ROOT/.github/action_scripts
    node send_transactions/token-locks.js $DAG_L0_PORT_PREFIX $DAG_L1_PORT_PREFIX $ML0_PORT_PREFIX $CL1_PORT_PREFIX $DL1_PORT_PREFIX
    show_time "Token lock tests completed"
  fi

  if should_run_test "allow-spends"; then
    echo "================================================"
    echo "Running allow-spend tests"
    echo "================================================"
    cd $PROJECT_ROOT/.github/action_scripts
    # Note: double-spend scenario requires extended DAG L1 (6 nodes) and is skipped here
    for scenario in dag currency exceeding-balance invalid-parent invalid-epoch invalid-signature expired-allow-spend double-use-allow-spend invalid-currency-destination invalid-approver; do
      echo "--- Allow-spend scenario: $scenario ---"
      node send_transactions/allow-spends-and-spend-transactions.js $DAG_L0_PORT_PREFIX $DAG_L1_PORT_PREFIX $ML0_PORT_PREFIX $CL1_PORT_PREFIX $DL1_PORT_PREFIX $scenario
    done
    show_time "Allow-spend tests completed"
  fi

  if should_run_test "spend"; then
    echo "================================================"
    echo "Running spend transaction tests"
    echo "================================================"
    cd $PROJECT_ROOT/.github/action_scripts
    for scenario in spend full-spend unauthorized unauthorized-currency exceeding-amount-spend; do
      echo "--- Spend scenario: $scenario ---"
      node send_transactions/allow-spends-and-spend-transactions.js $DAG_L0_PORT_PREFIX $DAG_L1_PORT_PREFIX $ML0_PORT_PREFIX $CL1_PORT_PREFIX $DL1_PORT_PREFIX $scenario
    done
    show_time "Spend transaction tests completed"
  fi

  if [ -n "$CI_PRIVATE_KEY" ]; then
    if should_run_test "data-without-fee"; then
      echo "================================================"
      echo "Running data transaction tests (without fee)"
      echo "================================================"
      cd $PROJECT_ROOT/.github/action_scripts
      node send_transactions/data-without-fee.js $DAG_L0_PORT_PREFIX $DAG_L1_PORT_PREFIX $ML0_PORT_PREFIX $CL1_PORT_PREFIX $DL1_PORT_PREFIX $CI_PRIVATE_KEY
      show_time "Data transaction tests (without fee) completed"
    fi

    if should_run_test "data-with-fee"; then
      echo "================================================"
      echo "Running data transaction tests (with fee)"
      echo "================================================"
      cd $PROJECT_ROOT/.github/action_scripts
      node send_transactions/data-with-fee.js $DAG_L0_PORT_PREFIX $DAG_L1_PORT_PREFIX $ML0_PORT_PREFIX $CL1_PORT_PREFIX $DL1_PORT_PREFIX $CI_PRIVATE_KEY
      show_time "Data transaction tests (with fee) completed"
    fi
  else
    echo "================================================"
    echo "Skipping data transaction tests (CI_PRIVATE_KEY not set)"
    echo "================================================"
  fi

else
  echo "================================================"
  echo "Skipping metagraph tests (no --metagraph or --use-test-metagraph flag)"
  echo "================================================"
fi

echo "------------------------------------------------"
echo "End-to-end tests completed"
echo "------------------------------------------------"

cd $PROJECT_ROOT




