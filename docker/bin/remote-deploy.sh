#!/usr/bin/env bash
# Remote deployment for compose-runner.sh
# Sourced (not executed) — all env vars from set-env.sh are available.
# NOTE: May call `exit` to terminate the parent (e.g., DOCKER_UP mode).

set -e

IFS=',' read -ra ALL_NODES <<< "$REMOTE_NODES"

# First 3 nodes run GL0/GL1, 4th (if present) runs snapshot-streaming
if [ "${#ALL_NODES[@]}" -ge 4 ]; then
  NODES=("${ALL_NODES[@]:0:3}")
  SS_NODE="${ALL_NODES[3]}"
else
  NODES=("${ALL_NODES[@]}")
  SS_NODE=""
fi
GENESIS_NODE="${NODES[0]}"
VALIDATORS=("${NODES[@]:1}")
NUM_NODES=${#NODES[@]}

COMPOSE="docker compose -f docker-compose.yaml -f docker-compose.host.yaml"
DIR="$REMOTE_DIR"
IMAGE="constellationnetwork/tessellation:nightly"
IMAGE_TAR="/tmp/remote-deploy/tessellation-nightly.tar.gz"
STAGING="/tmp/remote-deploy"

green() { printf "\033[32m%s\033[0m\n" "$*"; }
log()   { printf "\033[34m[%s]\033[0m %s\n" "$(date +%H:%M:%S)" "$*"; }

# --- Resolve IPs from SSH config ---
NODE_IPS=()
for h in "${NODES[@]}"; do
  NODE_IPS+=($(ssh -G "$h" | awk '/^hostname / {print $2}'))
done
if [ -n "$SS_NODE" ]; then
  SS_NODE_IP=$(ssh -G "$SS_NODE" | awk '/^hostname / {print $2}')
  log "Nodes: ${NODES[*]} -> IPs: ${NODE_IPS[*]}, SS: $SS_NODE -> $SS_NODE_IP"
else
  log "Nodes: ${NODES[*]} -> IPs: ${NODE_IPS[*]}"
fi

# --- Read node-0 identity from pre-generated test keys ---
NODE0_PEER_ID=$(cat "$PROJECT_ROOT/docker/config/local-test-keys/0/peer_id")
NODE0_ADDRESS=$(cat "$PROJECT_ROOT/docker/config/local-test-keys/0/address")

# === Phase 1: Build ===
if [ "$SKIP_ASSEMBLY" != "true" ]; then
  log "Running assembly"
  source ./docker/bin/assembly.sh
  show_time "Assembly"
fi

PLATFORM_FLAG=""
if [ "$(uname -m)" != "x86_64" ]; then
  PLATFORM_FLAG="--platform linux/amd64"
fi
log "Building Docker image (linux/amd64)"
docker build $PLATFORM_FLAG -t "$IMAGE" -f docker/Dockerfile .
show_time "Docker build"

log "Saving image"
rm -rf "$STAGING"
mkdir -p "$STAGING"
docker save "$IMAGE" | gzip > "$IMAGE_TAR"
log "Saved ($(du -h "$IMAGE_TAR" | cut -f1))"

# === Phase 2: Generate per-node configs ===
log "Generating configs"

cp "$PROJECT_ROOT/docker/docker-compose.yaml" "$STAGING/docker-compose.yaml"
cat > "$STAGING/docker-compose.host.yaml" <<'EOF'
services:
  gl0:
    network_mode: host
  gl1:
    network_mode: host
EOF

mkdir -p "$STAGING/node0"
cp "$PROJECT_ROOT/.github/config/genesis.csv" "$STAGING/node0/genesis.csv"
printf '\n%s,100000000000000\n' "$NODE0_ADDRESS" >> "$STAGING/node0/genesis.csv"

for i in $(seq 0 $((NUM_NODES - 1))); do
  mkdir -p "$STAGING/node$i"
  cp "$PROJECT_ROOT/docker/config/local-test-keys/$i/key.p12" "$STAGING/node$i/key.p12"

  cat > "$STAGING/node$i/.env" <<ENVEOF
TESSELLATION_DOCKER_VERSION=nightly
CL_APP_ENV=dev
CL_COLLATERAL=0
CL_TEST_MODE=true
CL_LOCAL_MODE=true

CL_DOCKER_GL0_PEER_HTTP_HOST=${NODE_IPS[0]}
CL_DOCKER_GL0_PEER_HTTP_PORT=9000
CL_DOCKER_GL0_PEER_ID=${NODE0_PEER_ID}
CL_GLOBAL_L0_PEER_ID=${NODE0_PEER_ID}

CL_DOCKER_INTERNAL_GL0_PUBLIC=9000
CL_DOCKER_INTERNAL_GL0_P2P=9001
CL_DOCKER_INTERNAL_GL0_CLI=9002
CL_DOCKER_INTERNAL_GL1_PUBLIC=9010
CL_DOCKER_INTERNAL_GL1_P2P=9011
CL_DOCKER_INTERNAL_GL1_CLI=9012

CL_EXTERNAL_IP=${NODE_IPS[$i]}
ENVEOF

  # ClickHouse logging (optional — enabled when CLICKHOUSE_HOST is set)
  if [ -n "$CLICKHOUSE_HOST" ]; then
    cat >> "$STAGING/node$i/.env" <<ENVEOF

CLICKHOUSE_HOST=${CLICKHOUSE_HOST}
CLICKHOUSE_USER=${CLICKHOUSE_USER:-default}
CLICKHOUSE_PASSWORD=${CLICKHOUSE_PASSWORD}
CLICKHOUSE_PORT=${CLICKHOUSE_PORT:-8443}
CLICKHOUSE_DATABASE=${CLICKHOUSE_DATABASE:-default}
CLICKHOUSE_PROTOCOL=${CLICKHOUSE_PROTOCOL:-https}
CLICKHOUSE_LOGS_TABLE_NAME=nightly_logs
ENVEOF
  fi

  if [ "$i" -eq 0 ]; then
    # Check if genesis data already exists on n0
    HAS_DATA=$(ssh "${NODES[0]}" "test -f $DIR/gl0-data/snapshot/ordinal/0/0 && echo yes || echo no" 2>/dev/null || echo "no")
    ROLLBACK_HASH=$(ssh "${NODES[0]}" "cat $DIR/.last-snapshot-hash 2>/dev/null" || echo "")
    if [ "$HAS_DATA" = "yes" ] && [ -n "$ROLLBACK_HASH" ]; then
      log "  Existing data detected, using rollback (hash: ${ROLLBACK_HASH:0:16}...)"
      cat >> "$STAGING/node$i/.env" <<ENVEOF
CL_DOCKER_GL0_GENESIS=true
CL_DOCKER_GL0_JOIN=false
CL_DOCKER_GL1_GENESIS=true
CL_DOCKER_GL1_JOIN=false
CL_DOCKER_ROLLBACK=true
CL_DOCKER_ROLLBACK_HASH=${ROLLBACK_HASH}
CL_GENESIS_FILE=./genesis.csv
ENVEOF
    else
      cat >> "$STAGING/node$i/.env" <<ENVEOF
CL_DOCKER_GL0_GENESIS=true
CL_DOCKER_GL0_JOIN=false
CL_DOCKER_GL1_GENESIS=true
CL_DOCKER_GL1_JOIN=false
CL_GENESIS_FILE=./genesis.csv
ENVEOF
    fi
  else
    gl0_delay=$((i * 5))
    gl1_delay=$((i * 5 + 55))
    cat >> "$STAGING/node$i/.env" <<ENVEOF
CL_DOCKER_GL0_GENESIS=false
CL_DOCKER_GL0_JOIN=true
CL_DOCKER_GL0_JOIN_ID=${NODE0_PEER_ID}
CL_DOCKER_GL0_JOIN_IP=${NODE_IPS[0]}
CL_DOCKER_GL0_JOIN_PORT=9001
CL_DOCKER_GL0_JOIN_INITIAL_DELAY=${gl0_delay}
CL_DOCKER_GL0_JOIN_RETRIES=20
CL_DOCKER_GL0_JOIN_DELAY=15
CL_DOCKER_GL1_GENESIS=false
CL_DOCKER_GL1_JOIN=true
CL_DOCKER_GL1_JOIN_ID=${NODE0_PEER_ID}
CL_DOCKER_GL1_JOIN_IP=${NODE_IPS[0]}
CL_DOCKER_GL1_JOIN_PORT=9011
CL_DOCKER_GL1_JOIN_INITIAL_DELAY=${gl1_delay}
CL_DOCKER_GL1_JOIN_RETRIES=20
CL_DOCKER_GL1_JOIN_DELAY=15
ENVEOF
  fi
done

show_time "Config generation"

# === Phase 3: Transfer ===
for i in $(seq 0 $((NUM_NODES - 1))); do
  h="${NODES[$i]}"
  log "Deploying to $h (${NODE_IPS[$i]})"
  ssh "$h" "mkdir -p $DIR/{gl0-logs,gl1-logs,gl0-data,gl1-data}"
  scp -q "$STAGING/docker-compose.yaml" "$STAGING/docker-compose.host.yaml" "$h:$DIR/"
  scp -q "$STAGING/node$i/.env" "$STAGING/node$i/key.p12" "$h:$DIR/"
  [ "$i" -eq 0 ] && scp -q "$STAGING/node0/genesis.csv" "$h:$DIR/"
  log "  Transferring image"
  scp -q "$IMAGE_TAR" "$h:$DIR/"
  log "  Loading image"
  ssh "$h" "docker load < $DIR/tessellation-nightly.tar.gz" 2>&1 | grep -v "^$"
  green "  $h ready"
done

show_time "Transfer"

# === Phase 4: Start cluster ===

# GL0 genesis
log "Starting GL0 genesis on $GENESIS_NODE"
ssh "$GENESIS_NODE" "cd $DIR && $COMPOSE --profile l0 up -d gl0" 2>&1 | grep -vE "variable is not set|Published ports"
GL0_GENESIS_OK=false
for i in $(seq 1 30); do
  state=$(ssh "$GENESIS_NODE" "curl -sf http://localhost:9000/node/info 2>/dev/null" \
    | python3 -c "import sys,json;print(json.load(sys.stdin).get('state',''))" 2>/dev/null || true)
  [ "$state" = "Ready" ] && green "  GL0-0 ready" && GL0_GENESIS_OK=true && break
  printf "  GL0-0: %s (%d/30)\n" "${state:-pending}" "$i"; sleep 10
done
[ "$GL0_GENESIS_OK" = "true" ] || { log "ERROR: GL0 genesis did not reach Ready within 5 minutes"; exit 1; }

# GL0 validators
log "Starting GL0 on validators"
for h in "${VALIDATORS[@]}"; do
  ssh "$h" "cd $DIR && $COMPOSE --profile l0 up -d gl0" 2>&1 | grep -vE "variable is not set|Published ports"
done
GL0_CLUSTER_OK=false
for i in $(seq 1 30); do
  count=$(ssh "$GENESIS_NODE" "curl -sf http://localhost:9000/cluster/info 2>/dev/null" \
    | python3 -c "import sys,json;print(len(json.load(sys.stdin)))" 2>/dev/null || echo 0)
  [ "$count" = "$NUM_NODES" ] && green "  GL0 cluster: $count/$NUM_NODES" && GL0_CLUSTER_OK=true && break
  printf "  GL0: %s/%s (%d/30)\n" "$count" "$NUM_NODES" "$i"; sleep 10
done
[ "$GL0_CLUSTER_OK" = "true" ] || { log "ERROR: GL0 cluster did not form within 5 minutes"; exit 1; }

# GL1 all nodes
log "Starting GL1 on all nodes"
for h in "${NODES[@]}"; do
  ssh "$h" "cd $DIR && $COMPOSE --profile l1 up -d gl1" 2>&1 | grep -vE "variable is not set|Published ports"
done
GL1_CLUSTER_OK=false
for i in $(seq 1 30); do
  count=$(ssh "$GENESIS_NODE" "curl -sf http://localhost:9010/cluster/info 2>/dev/null" \
    | python3 -c "import sys,json;print(len(json.load(sys.stdin)))" 2>/dev/null || echo 0)
  [ "$count" = "$NUM_NODES" ] && green "  GL1 cluster: $count/$NUM_NODES" && GL1_CLUSTER_OK=true && break
  printf "  GL1: %s/%s (%d/30)\n" "$count" "$NUM_NODES" "$i"; sleep 10
done
[ "$GL1_CLUSTER_OK" = "true" ] || { log "ERROR: GL1 cluster did not form within 5 minutes"; exit 1; }

show_time "Cluster startup"

# Status
log "Cluster status:"
for h in "${NODES[@]}"; do
  printf "  %s: " "$h"
  ssh "$h" "docker ps --filter name=gl --format '{{.Names}}({{.Status}})' | tr '\n' ' '"
  echo ""
done

ordinal=$(ssh "$GENESIS_NODE" "curl -sf http://localhost:9000/global-snapshots/latest" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['value']['ordinal'])" 2>/dev/null || echo "?")
green "Snapshots — ordinal: $ordinal"

# === Phase 5: Background tx-sender on genesis node ===
TX_SENDER_JAR="$PROJECT_ROOT/docker/jars/tools.jar"
TX_SENDER_CONF="$PROJECT_ROOT/docker/config/tx-sender.conf"
if [ -f "$TX_SENDER_JAR" ] && [ -s "$TX_SENDER_JAR" ] && [ -f "$TX_SENDER_CONF" ]; then
  log "Setting up tx-sender on $GENESIS_NODE"
  ssh "$GENESIS_NODE" "mkdir -p $DIR/tx-sender"
  scp -q "$TX_SENDER_JAR" "$GENESIS_NODE:$DIR/tx-sender/tools.jar"

  # Generate remote config pointing to localhost GL1 (host networking)
  sed 's|http://gl1-0:9100|http://localhost:9010|' "$TX_SENDER_CONF" \
    | ssh "$GENESIS_NODE" "cat > $DIR/tx-sender/tx-sender.conf"

  ssh "$GENESIS_NODE" "docker rm -f tx-sender 2>/dev/null || true"
  ssh "$GENESIS_NODE" "docker run -d --name tx-sender \
    --network host \
    --restart unless-stopped \
    -v $DIR/tx-sender/tools.jar:/app/tools.jar:ro \
    -v $DIR/tx-sender/tx-sender.conf:/app/tx-sender.conf:ro \
    eclipse-temurin:11-jre \
    java -jar /app/tools.jar tx-sender --config /app/tx-sender.conf" \
    > /dev/null 2>&1 && green "  tx-sender started" || log "  tx-sender failed (non-fatal)"
else
  log "Skipping tx-sender (tools.jar or tx-sender.conf not found)"
fi

# === Phase 6: Snapshot-streaming on 4th node ===
if [ -n "$SS_NODE" ]; then
  log "Setting up snapshot-streaming on $SS_NODE"
  SS_DIR="$PROJECT_ROOT/docker/snapshot-streaming"
  SS_REMOTE_DIR="$DIR/snapshot-streaming"

  # Build/obtain JAR — build-snapshot-streaming.sh handles reuse logic internally
  source "$SS_DIR/build-snapshot-streaming.sh"
  show_time "Snapshot-streaming build"

  # Generate application.conf for remote (host networking: postgres on localhost, GL0 on genesis IP)
  SS_CONFIG="$STAGING/ss-application.conf"
  cat > "$SS_CONFIG" <<'STATICEOF'
include classpath("application")
STATICEOF
  cat >> "$SS_CONFIG" <<CONFEOF
snapshotStreaming {
  environment = dev
  lastIncrementalSnapshotPath = "/app/data/seed-snapshot.json.gz"
  node {
    l0Peers = [{ id = "$NODE0_PEER_ID", ip = "${NODE_IPS[0]}", port = 9000 }]
    pullInterval = 5s
    pullLimit = 50
  }
  db {
    host = "127.0.0.1"
    port = 5432
    user = "snapshot_streaming"
    password = "snapshot_streaming"
    database = "snapshot_streaming"
    maxSessions = 16
  }
  opensearch {
    uri = "http://localhost:9200"
    bulkSize = 500
    indexes {
      snapshots = "snapshots"
      blocks = "blocks"
      transactions = "transactions"
      balances = "balances"
      currency {
        snapshots = "currency-snapshots"
        blocks = "currency-blocks"
        transactions = "currency-transactions"
        balances = "currency-balances"
        feeTransactions = "currency-fee-transactions"
      }
    }
  }
  s3 {
    bucketRegion = "us-west-2"
    bucketName = "disabled"
    bucketDir = "disabled"
    api {}
    uploadEnabled = false
  }
}
CONFEOF

  # Generate docker-compose for SS with host networking
  SS_COMPOSE="$STAGING/ss-docker-compose.yaml"
  cat > "$SS_COMPOSE" <<'DCEOF'
services:
  snapshot-streaming-postgres:
    image: postgres:15-alpine
    container_name: snapshot-streaming-postgres
    network_mode: host
    environment:
      POSTGRES_USER: snapshot_streaming
      POSTGRES_PASSWORD: snapshot_streaming
      POSTGRES_DB: snapshot_streaming
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U snapshot_streaming"]
      interval: 3s
      timeout: 5s
      retries: 10
      start_period: 5s
    volumes:
      - ss-pgdata:/var/lib/postgresql/data

  snapshot-streaming:
    image: eclipse-temurin:21-jre
    container_name: snapshot-streaming
    network_mode: host
    depends_on:
      snapshot-streaming-postgres:
        condition: service_healthy
    command: ["java", "-Xmx1g", "-Xms256m", "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED", "--add-opens=java.base/java.util=ALL-UNNAMED", "--add-opens=java.base/java.security=ALL-UNNAMED", "-Dconfig.file=/app/application.conf", "-cp", "/app/snapshot-streaming.jar", "org.constellation.snapshotstreaming.App"]
    volumes:
      - ./snapshot-streaming.jar:/app/snapshot-streaming.jar:ro
      - ./application.conf:/app/application.conf:ro
      - ./data:/app/data

  # serverless-offline exposes block_explorer's API on host:3001. Postgres lives
  # on host loopback (same node), so DATABASE_URL points at 127.0.0.1:5432.
  # node_modules is held in a named volume so re-deploys don't repeat npm ci.
  block-explorer:
    image: node:20-alpine
    container_name: block-explorer
    network_mode: host
    depends_on:
      snapshot-streaming-postgres:
        condition: service_healthy
    working_dir: /app
    environment:
      DATABASE_URL: "postgresql://snapshot_streaming:snapshot_streaming@127.0.0.1:5432/snapshot_streaming?schema=public"
    # serverless@3 is the last fully-open-source major; v4 requires a license.
    # block_explorer's package.json only lists serverless-offline (the plugin)
    # and assumes serverless cli is provided externally, so install it here
    # with --no-save so the bind-mounted package.json is left untouched.
    command: ["sh", "-c", "npm install --no-audit --no-fund --loglevel=error && npm install --no-save --no-package-lock --no-audit --no-fund --loglevel=error serverless@3 && npx serverless offline --host 0.0.0.0 --httpPort 3001"]
    volumes:
      - ./block-explorer:/app
      - block-explorer-node-modules:/app/node_modules
    restart: unless-stopped

volumes:
  ss-pgdata:
    name: ss-pgdata
  block-explorer-node-modules:
    name: block-explorer-node-modules
DCEOF

  # Transfer to SS node
  log "  Transferring to $SS_NODE"
  ssh "$SS_NODE" "mkdir -p $SS_REMOTE_DIR/data"
  scp -q "$SS_DIR/snapshot-streaming.jar" "$SS_NODE:$SS_REMOTE_DIR/"
  scp -q "$SS_CONFIG" "$SS_NODE:$SS_REMOTE_DIR/application.conf"
  scp -q "$SS_COMPOSE" "$SS_NODE:$SS_REMOTE_DIR/docker-compose.yaml"

  # Start postgres
  log "  Starting postgres on $SS_NODE"
  ssh "$SS_NODE" "cd $SS_REMOTE_DIR && docker compose up -d snapshot-streaming-postgres"
  # Probe TCP — pg_isready over unix socket succeeds against the init-phase
  # server before it's shut down and restarted for real.
  for attempt in $(seq 1 30); do
    if ssh "$SS_NODE" "docker exec snapshot-streaming-postgres pg_isready -h 127.0.0.1 -U snapshot_streaming" >/dev/null 2>&1; then
      log "  Postgres ready"; break
    fi
    sleep 2
  done

  # Transfer block_explorer source (prisma migrations + serverless API)
  log "  Transferring block_explorer to $SS_NODE"
  BE_DIR="$SS_DIR/block-explorer"
  # Replace any prior copy so a branch change can't leave stale files behind.
  # node_modules is held in a docker volume, not under block-explorer/, so this
  # rm doesn't trash installed deps.
  ssh "$SS_NODE" "rm -rf $SS_REMOTE_DIR/block-explorer"
  scp -rq "$BE_DIR" "$SS_NODE:$SS_REMOTE_DIR/block-explorer"
  # env.yml in the repo points at a local dev db; override so serverless-offline
  # reads from snapshot-streaming's postgres on this node.
  ssh "$SS_NODE" "cat > $SS_REMOTE_DIR/block-explorer/env.yml" <<'ENVEOF'
default:
  vpc: {}
  db_url: postgresql://snapshot_streaming:snapshot_streaming@127.0.0.1:5432/snapshot_streaming?schema=public
ENVEOF

  # Apply schema via prisma migrations
  log "  Applying database schema"
  ssh "$SS_NODE" "docker exec snapshot-streaming-postgres psql -U snapshot_streaming -d snapshot_streaming -c 'DROP SCHEMA public CASCADE; CREATE SCHEMA public;'" 2>/dev/null || true
  ssh "$SS_NODE" "docker run --rm --network host -v $SS_REMOTE_DIR/block-explorer/prisma:/app/prisma -w /app -e DATABASE_URL='postgresql://snapshot_streaming:snapshot_streaming@127.0.0.1:5432/snapshot_streaming' node:20-alpine sh -c 'npx prisma@6.2.1 db push --accept-data-loss --force-reset'" 2>&1 | tail -3

  # Seed with initial snapshot from GL0
  log "  Seeding from GL0"
  gl0_url="http://${NODE_IPS[0]}:9000"
  for attempt in $(seq 1 60); do
    latest_ord=$(curl -sf "$gl0_url/global-snapshots/latest/ordinal" 2>/dev/null | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('value',d) if isinstance(d,dict) else d)" 2>/dev/null || echo 0)
    [ "$latest_ord" -ge 2 ] 2>/dev/null && break
    sleep 3
  done

  combined_json=$(curl -sf "$gl0_url/global-snapshots/latest/combined")
  seed_ordinal=$(echo "$combined_json" | python3 -c "import sys,json;print(json.load(sys.stdin)[0]['value']['ordinal'])")
  snapshot_hash=$(curl -sf "$gl0_url/global-snapshots/$seed_ordinal/hash" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('value',d) if isinstance(d,dict) else d)" 2>/dev/null | tr -d '"')
  proofs_hash=$(echo "$combined_json" | python3 -c "import sys,json;print(json.dumps(json.load(sys.stdin)[0]['proofs'],sort_keys=True,separators=(',',':')))" | shasum -a 256 | awk '{print $1}')
  log "  Seed ordinal: $seed_ordinal, hash: ${snapshot_hash:0:16}..."

  echo "$combined_json" | python3 -c "
import sys,json
d=json.load(sys.stdin)
print(json.dumps({'snapshot':{'signed':d[0],'hash':'$snapshot_hash','proofsHash':'$proofs_hash'},'state':d[1]}))" \
    | gzip | ssh "$SS_NODE" "cat > $SS_REMOTE_DIR/data/seed-snapshot.json.gz"

  # Start snapshot-streaming
  log "  Starting snapshot-streaming"
  ssh "$SS_NODE" "cd $SS_REMOTE_DIR && docker compose up -d snapshot-streaming"

  # Start block-explorer (serverless-offline API on :3001 against the SS postgres)
  log "  Starting block-explorer"
  ssh "$SS_NODE" "cd $SS_REMOTE_DIR && docker compose up -d block-explorer"

  # Wait for indexing to start
  for attempt in $(seq 1 30); do
    count=$(ssh "$SS_NODE" "docker exec snapshot-streaming-postgres psql -U snapshot_streaming -d snapshot_streaming -t -A -c 'SELECT COUNT(*) FROM global_snapshots;'" 2>/dev/null || echo 0)
    if [ "$count" -ge 3 ] 2>/dev/null; then
      green "  Snapshot-streaming indexing: $count snapshots"
      break
    fi
    printf "  Indexing: %s snapshots (%d/30)\n" "$count" "$attempt"
    sleep 5
  done

  show_time "Snapshot-streaming"
fi

# Set TEST_HOST so E2E tests can run against remote cluster
export TEST_HOST="http://${NODE_IPS[0]}"
export GL0_URL="${TEST_HOST}:9000"
export GL1_URL="${TEST_HOST}:9010"

if [ "$DOCKER_UP" = "true" ]; then
  log "Up mode, skipping tests"
  exit 0
fi
