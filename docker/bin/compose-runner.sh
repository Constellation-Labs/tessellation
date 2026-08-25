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
  docker rm -f tx-sender 2>/dev/null || true
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
  echo "  fork-recovery            Fork recovery test (needs --num-gl0=5)"
  echo "  committee-rewards        Full-committee delegated reward split"
  echo "                           (needs --num-gl0=5 --num-gl0-early=3;"
  echo "                            --gl0-late-delay=<seconds>, default 240, tunes the join stagger)"
  echo "  token-lock-replacement   Token lock replacement edge case tests"
  echo "  snapshot-streaming       Snapshot streaming indexer E2E test"
  echo ""
  echo "Metagraph tests (require --use-test-metagraph):"
  echo "  currency                 Metagraph currency transaction tests"
  echo "  rewards                  Metagraph rewards tests"
  echo "  token-locks              Token lock tests"
  echo "  allow-spends             Allow-spend tests"
  echo "  spend                    Spend transaction tests"
  echo "  data-without-fee            Data transaction tests (UsageUpdateNoFee, no fee required)"
  echo "  data-with-fee               Data transaction tests (adequate fee, verifies getSnapshotFeeTransactions lookup)"
  echo "  data-with-insufficient-fee  Data transaction tests (fee below minimum, expect rejection)"
  echo "  data-with-missing-fee       Data transaction tests (fee-required update sent with no fee, expect rejection)"
  echo ""
  echo "Usage: just test --test=dag-cluster --test=delegated-staking"
  echo "       just test --test=dag-cluster,rewards    (comma-separated)"
  echo "       just test --skip-streaming              (run everything except the snapshot-streaming build+test)"
  exit 0
fi

# If tests are selected, check if any require metagraph. If not, skip metagraph setup.
METAGRAPH_TESTS="currency,rewards,token-locks,allow-spends,spend,data-without-fee,data-with-fee,data-with-insufficient-fee,data-with-missing-fee"
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

# Whether to build + run the snapshot-streaming test. Skipped entirely when --skip-streaming is set
# (avoids the heavyweight ss build + GitHub Packages auth for local metagraph iteration); otherwise
# runs when no specific tests are selected or snapshot-streaming is among them.
streaming_enabled() {
  [ "$SKIP_STREAMING" = "true" ] && return 1
  [ -z "$SELECTED_TESTS" ] && return 0
  echo "$SELECTED_TESTS" | tr ',' '\n' | grep -qx "snapshot-streaming"
}

# snapshot-streaming build-from-source needs sdk/publishLocal
if streaming_enabled; then
  export PUBLISH=${PUBLISH:-true}
fi

REMOTE_HOST=${TEST_HOST:-}

if [ -n "$REMOTE_NODES" ] && [ -z "$REMOTE_HOST" -o "$REMOTE_HOST" = "http://localhost" ]; then
  echo "------------------------------------------------"
  echo "Remote deployment to: $REMOTE_NODES"
  echo "------------------------------------------------"
  source ./docker/bin/remote-deploy.sh
elif [ -n "$REMOTE_HOST" ] && [ "$REMOTE_HOST" != "http://localhost" ]; then
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

  # Copy keytool and wallet jars to nodes directory for key generation (needed for nodes 3+)
  cp ./docker/jars/keytool.jar ./docker/jars/wallet.jar ./nodes/ 2>/dev/null || true

  source ./docker/bin/node-key-env-setup.sh
  source ./docker/bin/docker-env-setup.sh


  echo "------------------------------------------------"
  echo "All deployment configurations now generated, proceeding to run cluster"
  echo "------------------------------------------------"


  # Build snapshot-streaming JAR (needed before BUILD_ONLY exit so `just build` produces it)
  if streaming_enabled; then
    SS_DIR="$PROJECT_ROOT/docker/snapshot-streaming"
    source "$SS_DIR/build-snapshot-streaming.sh"
    cd "$PROJECT_ROOT"
  fi

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

    cd ../../
  done

  # Start all GL0 nodes together
  for i in $(seq 0 $((NUM_GL0_NODES - 1))); do
    cd ./nodes/$i/
    docker compose -f docker-compose.test.yaml \
      -f docker-compose.yaml \
      -f docker-compose.volumes.yaml \
      --profile l0 \
      up -d
    cd ../../
  done

  # Wait for GL0 cluster to be ready before starting GL1
  # GL1 needs GL0 for L0PeerDiscovery; without this, GL1's join state machine
  # gets stuck at SessionStarted.
  if [ "$NUM_GL0_NODES" -gt 0 ] && [ "$NUM_GL1_NODES" -gt 0 ]; then
    echo "Waiting for GL0 cluster to be ready before starting GL1..."
    gl0_url="${TEST_HOST:-http://localhost}:${DAG_L0_PORT_PREFIX}00"
    gl0_ready=false
    for attempt in $(seq 1 120); do
      cluster_info=$(curl -s "${gl0_url}/cluster/info" 2>/dev/null || echo "")
      if [ -n "$cluster_info" ] && echo "$cluster_info" | jq 'length' >/dev/null 2>&1; then
        node_count=$(echo "$cluster_info" | jq 'length')
        if [ "$node_count" -ge 1 ]; then
          echo "GL0 cluster ready with $node_count node(s)"
          gl0_ready=true
          break
        fi
      fi
      echo "GL0 not ready yet (attempt $attempt/120), waiting..."
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
      for attempt in $(seq 1 120); do
        cluster_info=$(curl -s "${ml0_url}/cluster/info" 2>/dev/null || echo "")
        if [ -n "$cluster_info" ] && echo "$cluster_info" | jq 'length' >/dev/null 2>&1; then
          node_count=$(echo "$cluster_info" | jq 'length')
          if [ "$node_count" -ge 1 ]; then
            echo "ML0 is ready with $node_count node(s)"
            ml0_ready=true
            break
          fi
        fi
        echo "ML0 not ready yet (attempt $attempt/120), waiting..."
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


  # --- Snapshot-streaming infrastructure ---
  if streaming_enabled; then
    echo "================================================"
    echo "Setting up snapshot-streaming infrastructure"
    echo "================================================"

    SS_DIR="$PROJECT_ROOT/docker/snapshot-streaming"

    # Build/obtain JAR + SQL
    source "$SS_DIR/build-snapshot-streaming.sh"

    # Generate application.conf
    source "$SS_DIR/generate-config.sh"

    export SS_JAR_PATH="$SS_DIR/snapshot-streaming.jar"
    export SS_CONFIG_PATH="$SS_DIR/application.conf"
    export SS_DATA_PATH="$SS_DIR/data"
    mkdir -p "$SS_DATA_PATH"

    # Start postgres
    echo "Starting snapshot-streaming-postgres..."
    docker compose -f "$SS_DIR/docker-compose.yaml" up -d snapshot-streaming-postgres

    # Wait for postgres healthy.
    # Postgres' docker entrypoint starts a temporary unix-socket-only server during
    # init, then shuts it down, then starts the real server listening on TCP.
    # `pg_isready` (unix socket) and psql queries will both succeed against the
    # init-phase server, then race with the shutdown window. Probe TCP instead —
    # TCP is only enabled after init is fully complete.
    echo "Waiting for snapshot-streaming-postgres to be healthy..."
    for attempt in $(seq 1 60); do
      if docker exec snapshot-streaming-postgres pg_isready -h 127.0.0.1 -U snapshot_streaming >/dev/null 2>&1; then
        echo "snapshot-streaming-postgres is ready"
        break
      fi
      if [ "$attempt" -eq 60 ]; then
        echo "ERROR: snapshot-streaming-postgres did not become ready"
        docker logs snapshot-streaming-postgres || true
        exit 1
      fi
      sleep 2
    done

    # Apply database schema via block_explorer prisma migrations
    echo "Applying database schema via block_explorer prisma..."
    BE_DIR="$SS_DIR/block-explorer"
    SS_PG_IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' snapshot-streaming-postgres)
    DATABASE_URL="postgresql://snapshot_streaming:snapshot_streaming@${SS_PG_IP}:5432/snapshot_streaming"
    # Reset schema to ensure clean state
    docker exec snapshot-streaming-postgres psql -U snapshot_streaming -d snapshot_streaming \
      -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
    docker run --rm \
      --network tessellation_common \
      -v "$BE_DIR/prisma:/app/prisma" \
      -w /app \
      -e DATABASE_URL="$DATABASE_URL" \
      node:20-alpine \
      sh -c "npx prisma@6.2.1 db push --accept-data-loss --force-reset"
    echo "Database schema applied"

    # Seed snapshot-streaming with initial snapshot from GL0
    echo "Seeding snapshot-streaming with initial snapshot from GL0..."
    gl0_seed_url="${TEST_HOST:-http://localhost}:${DAG_L0_PORT_PREFIX}00"

    # Wait for GL0 to have a few snapshots
    for attempt in $(seq 1 120); do
      ordinal_resp=$(curl -sf "$gl0_seed_url/global-snapshots/latest/ordinal" 2>/dev/null || echo "")
      if [ -n "$ordinal_resp" ]; then
        latest_ord=$(echo "$ordinal_resp" | jq -r 'if type == "object" then .value else . end' 2>/dev/null || echo "0")
        if [ "$latest_ord" -ge 2 ] 2>/dev/null; then
          echo "GL0 has snapshots up to ordinal $latest_ord"
          break
        fi
      fi
      if [ "$attempt" -eq 120 ]; then
        echo "ERROR: GL0 did not produce enough snapshots for seeding"
        exit 1
      fi
      sleep 3
    done

    # Fetch latest combined snapshot + state
    combined_json=$(curl -sf "$gl0_seed_url/global-snapshots/latest/combined")
    seed_ordinal=$(echo "$combined_json" | jq '.[0].value.ordinal')
    echo "Fetched combined snapshot at ordinal $seed_ordinal"

    # Fetch the correct hash for this ordinal
    hash_resp=$(curl -sf "$gl0_seed_url/global-snapshots/$seed_ordinal/hash")
    snapshot_hash=$(echo "$hash_resp" | jq -r 'if type == "string" then . else .value // . end' 2>/dev/null || echo "$hash_resp")
    # Strip any surrounding quotes
    snapshot_hash=$(echo "$snapshot_hash" | tr -d '"')
    echo "Snapshot hash: ${snapshot_hash:0:16}..."

    # Compute proofsHash: SHA256 of the sorted proofs JSON (compact, sorted keys, no nulls).
    # Note: The node uses Brotli-compressed JSON for hashing, which we cannot replicate here.
    # This simplified hash is sufficient for the E2E test since snapshot-streaming does not
    # cryptographically verify the seed proofsHash.
    proofs_hash=$(echo "$combined_json" | jq -cS '.[0].proofs' | shasum -a 256 | awk '{print $1}')
    echo "Proofs hash: ${proofs_hash:0:16}..."

    # Create SnapshotWithState seed file (gzipped JSON)
    echo "$combined_json" | jq --arg h "$snapshot_hash" --arg ph "$proofs_hash" '{
      snapshot: {
        signed: .[0],
        hash: $h,
        proofsHash: $ph
      },
      state: .[1]
    }' | gzip > "$SS_DATA_PATH/seed-snapshot.json.gz"
    echo "Seed file created at $SS_DATA_PATH/seed-snapshot.json.gz"

    # Start snapshot-streaming
    echo "Starting snapshot-streaming..."
    docker compose -f "$SS_DIR/docker-compose.yaml" up -d snapshot-streaming

    show_time "Snapshot-streaming infrastructure started"
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
npm ci

if [ -z "$REMOTE_HOST" ] || [ "$REMOTE_HOST" = "http://localhost" ]; then
  sleep 10
  docker logs gl0-0
  echo "GL0-0 logs above, now continuing with cluster health check."
fi

source ../../docker/bin/cluster-health-check.sh
verify_healthy
show_time "Cluster became healthy"

# ------------------------------------------------
# Start background transaction sender (keeps EventTrigger flowing)
# ------------------------------------------------
TX_SENDER_JAR="$PROJECT_ROOT/docker/jars/tools.jar"
TX_SENDER_CONF="$PROJECT_ROOT/docker/config/tx-sender.conf"
if [ -f "$TX_SENDER_JAR" ] && [ -f "$TX_SENDER_CONF" ]; then
  echo "Starting background transaction sender..."
  docker rm -f tx-sender 2>/dev/null || true
  docker run -d --name tx-sender \
    --network tessellation_common \
    --restart unless-stopped \
    -v "$TX_SENDER_JAR:/app/tools.jar:ro" \
    -v "$TX_SENDER_CONF:/app/tx-sender.conf:ro" \
    eclipse-temurin:11-jre \
    java -jar /app/tools.jar tx-sender --config /app/tx-sender.conf \
    > /dev/null 2>&1 && echo "  tx-sender started" || echo "  tx-sender failed to start (non-fatal)"
else
  echo "Skipping background tx-sender (tools.jar or tx-sender.conf not found)"
fi

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

if should_run_test "fork-recovery"; then
  echo "================================================"
  echo "Running fork-recovery test"
  echo "================================================"
  cd $PROJECT_ROOT
  bash docker/bin/test-fork-recovery.sh $DAG_L0_PORT_PREFIX
  show_time "Fork recovery test completed"
fi

if should_run_test "committee-rewards"; then
  echo "================================================"
  echo "Running committee rewards test"
  echo "================================================"
  # This test needs a committee that mixes a promote-qualified peer with a chronic, non-promotable
  # one -- the only state where the removed payout filter and current behavior differ. The
  # staggered-join rig produces that reliably by keeping the committee churning: late joiners are
  # admitted, are recorded as non-responders (their Facility reaches the leader late, so they never
  # enter completedSigners and never gain score), go chronic, get dropped, and are re-admitted.
  # NOTE the mechanism is churn, not a late joiner "climbing" to Core -- on a loaded box a late
  # joiner's score never rises. See reference_completedsigners_is_responders.
  #   just test --test=committee-rewards --num-gl0=5 --num-gl0-early=3
  # A bare `just test` runs every registered test, so on a default 3-node run without the rig we
  # skip rather than burn the retry budget and fail. When the test was asked for BY NAME the rig is
  # a hard requirement and a missing one is an error, not a skip.
  if [ "${NUM_GL0_NODES:-0}" -lt 5 ] || [ -z "${NUM_GL0_EARLY:-}" ] || [ "${NUM_GL0_EARLY}" -ge "${NUM_GL0_NODES:-0}" ]; then
    msg="committee-rewards needs --num-gl0=5 --num-gl0-early=3 (got num-gl0=${NUM_GL0_NODES:-unset}, num-gl0-early=${NUM_GL0_EARLY:-unset})"
    if [ -n "$SELECTED_TESTS" ]; then
      echo "ERROR: $msg"
      exit 1
    fi
    echo "SKIPPING: $msg"
  else
    cd $PROJECT_ROOT/.github/action_scripts
    NUM_GL0_NODES=$NUM_GL0_NODES NUM_GL0_EARLY=$NUM_GL0_EARLY \
      node committee_rewards.js $DAG_L0_PORT_PREFIX $DAG_L1_PORT_PREFIX
    show_time "Committee rewards test completed"
  fi
fi

if streaming_enabled; then
  echo "================================================"
  echo "Running snapshot-streaming E2E test"
  echo "================================================"
  # Stop tx-sender before snapshot-streaming: the Prisma schema requires
  # dag_transactions.snapshot_ordinal NOT NULL but the trigger that populates it
  # from global_snapshots.hash is racy — if tx lands before the snapshot row
  # exists, snapshot_ordinal stays NULL and the insert fails.
  docker rm -f tx-sender 2>/dev/null || true
  cd $PROJECT_ROOT

  # Determine how to query postgres: local docker exec or remote SSH
  if [ -n "$REMOTE_NODES" ]; then
    IFS=',' read -ra _SS_NODES <<< "$REMOTE_NODES"
    if [ "${#_SS_NODES[@]}" -ge 4 ]; then
      SS_TEST_NODE="${_SS_NODES[3]}"
      ss_psql() { ssh "$SS_TEST_NODE" "docker exec snapshot-streaming-postgres psql -U snapshot_streaming -d snapshot_streaming $(printf '%q ' "$@")" 2>/dev/null; }
      ss_logs() { ssh "$SS_TEST_NODE" "docker logs snapshot-streaming 2>&1 | tail -100"; }
      echo "Testing snapshot-streaming on remote node: $SS_TEST_NODE"
    else
      ss_psql() { docker exec snapshot-streaming-postgres psql -U snapshot_streaming -d snapshot_streaming "$@" 2>/dev/null; }
      ss_logs() { docker logs snapshot-streaming 2>&1 | tail -100 || true; }
    fi
  else
    ss_psql() { docker exec snapshot-streaming-postgres psql -U snapshot_streaming -d snapshot_streaming "$@" 2>/dev/null; }
    ss_logs() { docker logs snapshot-streaming 2>&1 | tail -100 || true; }
  fi

  ss_test_passed=false
  echo "Waiting for snapshot-streaming to index snapshots..."
  for attempt in $(seq 1 120); do
    count=$(ss_psql -t -A -c "SELECT COUNT(*) FROM global_snapshots;" || echo "0")
    count=$(echo "$count" | tr -d '[:space:]')

    if [ "$count" -ge 3 ]; then
      echo "snapshot-streaming indexed $count global snapshots"
      max_ordinal=$(ss_psql -t -A -c "SELECT MAX(ordinal) FROM global_snapshots;" || echo "0")
      max_ordinal=$(echo "$max_ordinal" | tr -d '[:space:]')
      echo "Max ordinal: $max_ordinal"
      if [ "$max_ordinal" -gt 0 ]; then
        ss_test_passed=true
        break
      fi
    fi

    if [ "$((attempt % 10))" -eq 0 ]; then
      echo "  snapshot count: ${count:-0} (attempt $attempt/120)"
    fi
    sleep 5
  done

  if [ "$ss_test_passed" = "true" ]; then
    echo "snapshot-streaming E2E test PASSED"
  else
    echo "snapshot-streaming E2E test FAILED"
    echo "--- snapshot-streaming logs ---"
    ss_logs
    echo "--- postgres tables ---"
    ss_psql -c '\dt' || true
    exit 1
  fi
  show_time "Snapshot-streaming E2E test completed"
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

  if should_run_test "data-without-fee"; then
    echo "================================================"
    echo "Running data transaction tests (without fee)"
    echo "================================================"
    cd $PROJECT_ROOT/.github/action_scripts
    node send_transactions/data-without-fee.js $DAG_L0_PORT_PREFIX $DAG_L1_PORT_PREFIX $ML0_PORT_PREFIX $CL1_PORT_PREFIX $DL1_PORT_PREFIX
    show_time "Data transaction tests (without fee) completed"
  fi

  if should_run_test "data-with-fee"; then
    echo "================================================"
    echo "Running data transaction tests (adequate fee, expect fee looked up in combine)"
    echo "================================================"
    cd $PROJECT_ROOT/.github/action_scripts
    node send_transactions/data-with-fee.js $DAG_L0_PORT_PREFIX $DAG_L1_PORT_PREFIX $ML0_PORT_PREFIX $CL1_PORT_PREFIX $DL1_PORT_PREFIX
    show_time "Data transaction tests (adequate fee) completed"
  fi

  if should_run_test "data-with-insufficient-fee"; then
    echo "================================================"
    echo "Running data transaction tests (insufficient fee, expect rejection)"
    echo "================================================"
    cd $PROJECT_ROOT/.github/action_scripts
    node send_transactions/data-invalid-fee.js $DAG_L0_PORT_PREFIX $DAG_L1_PORT_PREFIX $ML0_PORT_PREFIX $CL1_PORT_PREFIX $DL1_PORT_PREFIX insufficient
    show_time "Data transaction tests (insufficient fee) completed"
  fi

  if should_run_test "data-with-missing-fee"; then
    echo "================================================"
    echo "Running data transaction tests (missing fee, expect rejection)"
    echo "================================================"
    cd $PROJECT_ROOT/.github/action_scripts
    node send_transactions/data-invalid-fee.js $DAG_L0_PORT_PREFIX $DAG_L1_PORT_PREFIX $ML0_PORT_PREFIX $CL1_PORT_PREFIX $DL1_PORT_PREFIX missing
    show_time "Data transaction tests (missing fee) completed"
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

