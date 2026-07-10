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
# IMAGE_SOURCE=build (default): build the image locally + ship it as a tarball.
# IMAGE_SOURCE=registry: pull a prebuilt image (e.g. from GHCR) on each node.
IMAGE_SOURCE="${IMAGE_SOURCE:-build}"
if [ "$IMAGE_SOURCE" = "registry" ]; then
  : "${CL_DOCKER_CORE_IMAGE:?IMAGE_SOURCE=registry requires CL_DOCKER_CORE_IMAGE}"
  : "${TESSELLATION_DOCKER_VERSION:?IMAGE_SOURCE=registry requires TESSELLATION_DOCKER_VERSION}"
  IMAGE="${CL_DOCKER_CORE_IMAGE}:${TESSELLATION_DOCKER_VERSION}"
  ENV_DOCKER_VERSION="$TESSELLATION_DOCKER_VERSION"
  ENV_CORE_IMAGE="$CL_DOCKER_CORE_IMAGE"
else
  IMAGE="constellationnetwork/tessellation:nightly"
  ENV_DOCKER_VERSION="nightly"
  ENV_CORE_IMAGE=""
fi
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

# --- Node identities ---
# PEER_IDS[i] = each node's public peer id, needed to wire configs (node0's id for
# consensus/join, all ids for snapshot-streaming l0Peers). Two sources:
#
#   PRESET_KEYS=true — keys are ALREADY on the nodes (e.g. migrated tn key.p12). The
#     deploy ships NO key material and never overwrites the on-node key; each peer id
#     is derived from that node's on-node key.p12 via the image's wallet jar.
#     NODE_KEY_ALIAS/PASSWORD (default keyalias/password) open the keystore + go into
#     the .env for runtime. Requires a registry image.
#   otherwise — NODE_KEYS_DIR/<i>/{key.p12,peer_id[,address]} (default = committed test
#     keys); key.p12 is staged and shipped to each node.
PRESET_KEYS="${PRESET_KEYS:-false}"
declare -a PEER_IDS
if [ "$PRESET_KEYS" = "true" ]; then
  NODE_KEY_ALIAS="${NODE_KEY_ALIAS:-keyalias}"
  NODE_KEY_PASSWORD="${NODE_KEY_PASSWORD:-password}"
  : "${CL_DOCKER_CORE_IMAGE:?PRESET_KEYS requires a registry image (IMAGE_SOURCE=registry)}"
  _derive_img="${CL_DOCKER_CORE_IMAGE}:${TESSELLATION_DOCKER_VERSION}"
  log "PRESET_KEYS: keys already on nodes — deriving peer ids via $_derive_img"
  for i in $(seq 0 $((NUM_NODES - 1))); do
    ssh "${NODES[$i]}" "docker image inspect $_derive_img >/dev/null 2>&1 || docker pull $_derive_img >/dev/null"
    PEER_IDS[$i]=$(ssh "${NODES[$i]}" "docker run --rm --entrypoint java -v $DIR/key.p12:/k.p12 -e CL_KEYSTORE=/k.p12 -e CL_KEYALIAS=$NODE_KEY_ALIAS -e CL_PASSWORD=$NODE_KEY_PASSWORD $_derive_img -jar /tessellation/jars/wallet.jar show-id 2>/dev/null" | grep -oE '[0-9a-f]{128}' | head -1)
    [ -n "${PEER_IDS[$i]}" ] || { log "ERROR: could not derive peer id from on-node key on ${NODES[$i]} (wrong NODE_KEY_ALIAS/PASSWORD, or key.p12 missing?)"; exit 1; }
    log "  ${NODES[$i]}: ${PEER_IDS[$i]:0:16}..."
  done
  NODE0_ADDRESS=""
else
  # NODE_KEYS_DIR: per-node key material, layout <dir>/<i>/{key.p12,peer_id[,address]}.
  NODE_KEYS_DIR="${NODE_KEYS_DIR:-$PROJECT_ROOT/docker/config/local-test-keys}"
  for i in $(seq 0 $((NUM_NODES - 1))); do
    PEER_IDS[$i]=$(cat "$NODE_KEYS_DIR/$i/peer_id")
  done
  # address is only needed to fund node0 in genesis.csv — absent for real keys.
  NODE0_ADDRESS=$(cat "$NODE_KEYS_DIR/0/address" 2>/dev/null || echo "")
fi
NODE0_PEER_ID="${PEER_IDS[0]}"

# Node phases (build -> ship -> start -> tx-sender). Skipped with --skip-nodes so that
# snapshot-streaming / monitoring can be (re)deployed without restarting node containers
# (restarting node-0 trips the genesis guard on a live chain). Default: run them, so
# nightly behaviour is unchanged.
# Staging dir holds both node and snapshot-streaming generated configs. Phase 6 (SS)
# writes here even under --skip-nodes, so create it OUTSIDE the node-phase guard —
# otherwise a cold --skip-nodes run (fresh runner / cleared /tmp) has no $STAGING and
# aborts under set -e when Phase 6 does `cat > $STAGING/ss-*`.
rm -rf "$STAGING"
mkdir -p "$STAGING"

if [ "$SKIP_NODES" != "true" ]; then
# === Phase 1: Build image (skipped when pulling from a registry) ===
if [ "$IMAGE_SOURCE" = "registry" ]; then
  log "IMAGE_SOURCE=registry — skipping local build; nodes will pull $IMAGE"
else
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
docker save "$IMAGE" | gzip > "$IMAGE_TAR"
log "Saved ($(du -h "$IMAGE_TAR" | cut -f1))"
fi

# === Phase 2: Generate per-node configs ===
log "Generating configs"

# Environment profile. DEPLOY_APP_ENV=dev (default) keeps the historical nightly
# behavior: CL_TEST_MODE/CL_LOCAL_MODE, no seedlist, no snapshot allowlist.
# Any other value (e.g. testnet) generates real-testnet-parity config matching
# tn1-3: seedlist gating on gl0, per-IP snapshot rate-limit allowlist, stored
# incremental snapshot path, MPT debug dump — and drops the test/local flags.
DEPLOY_APP_ENV="${DEPLOY_APP_ENV:-dev}"
if [ "$DEPLOY_APP_ENV" != "dev" ]; then
  if [ -n "$SEEDLIST_FILE" ]; then
    # Real seedlist (e.g. the 61-entry testnet-seedlist from tn1-3) for the migration —
    # requires the nodes to run the matching real keys or joins get gated out.
    cp "$SEEDLIST_FILE" "$STAGING/seedlist"
    SEEDLIST_SHIP=true
    SEEDLIST_DESC="$(wc -l < "$STAGING/seedlist" | tr -d ' ') ids from $SEEDLIST_FILE"
  elif [ "$PRESET_KEYS" = "true" ]; then
    # Keys (and their seedlist) are already on the nodes — reuse the on-node seedlist
    # rather than regenerating a partial one from just this cluster's ids.
    SEEDLIST_SHIP=false
    SEEDLIST_DESC="reusing on-node $DIR/seedlist"
  else
    # Seedlist = this cluster's own node ids (mechanism parity for staging clusters)
    : > "$STAGING/seedlist"
    for i in $(seq 0 $((NUM_NODES - 1))); do
      printf '%s\n' "${PEER_IDS[$i]}" >> "$STAGING/seedlist"
    done
    SEEDLIST_SHIP=true
    SEEDLIST_DESC="$NUM_NODES cluster ids"
  fi
  # Exempt cluster traffic from the per-IP snapshot rate limiter: nodes pull from
  # each other, snapshot-streaming bulk-pulls, and SNAPSHOT_ALLOWLIST_EXTRA lets
  # the caller add hosts (deploy-cluster.sh passes the monitoring IP).
  ALLOWLIST="$(IFS=,; echo "${NODE_IPS[*]}")"
  [ -n "$SS_NODE_IP" ] && ALLOWLIST="$ALLOWLIST,$SS_NODE_IP"
  [ -n "$SNAPSHOT_ALLOWLIST_EXTRA" ] && ALLOWLIST="$ALLOWLIST,$SNAPSHOT_ALLOWLIST_EXTRA"
  ALLOWLIST="$ALLOWLIST,127.0.0.1"
  ENV_PROFILE_BLOCK="CL_SNAPSHOT_PER_IP_ALLOWLIST=$ALLOWLIST
CL_MPT_DEBUG_DUMP=true
CL_DOCKER_SEEDLIST=$DIR/seedlist"
  # tn1-3 remap the FULL-snapshot path (snapshot-path) via CL_SNAPSHOT_STORED_PATH=
  # data/incremental_snapshot. Only set this when deploying onto data with that layout
  # (SNAPSHOT_STORED_PATH=data/incremental_snapshot for the tn migration) — on a fresh
  # genesis it makes run-genesis write the full genesis snapshot where the incremental
  # store reads, breaking snapshot serving (validated live 2026-07-01).
  [ -n "$SNAPSHOT_STORED_PATH" ] && ENV_PROFILE_BLOCK="$ENV_PROFILE_BLOCK
CL_SNAPSHOT_STORED_PATH=$SNAPSHOT_STORED_PATH"
  log "  Environment profile: $DEPLOY_APP_ENV (seedlist: $SEEDLIST_DESC, allowlist: $ALLOWLIST)"
else
  ENV_PROFILE_BLOCK="CL_TEST_MODE=true
CL_LOCAL_MODE=true"
fi
# Real keys usually differ from the image defaults (test keys: alias "alias"; tn1-3:
# alias "keyalias") — pass through when set, any DEPLOY_APP_ENV.
[ -n "$NODE_KEY_ALIAS" ] && ENV_PROFILE_BLOCK="$ENV_PROFILE_BLOCK
CL_KEYALIAS=$NODE_KEY_ALIAS"
[ -n "$NODE_KEY_PASSWORD" ] && ENV_PROFILE_BLOCK="$ENV_PROFILE_BLOCK
CL_PASSWORD=$NODE_KEY_PASSWORD"
# JVM profile for gl0 (tn parity: ZGC 10g). Interpreted by the tooling-shipped
# entrypoint (default-if-unset G1 2-8g when absent). gl1 keeps the default unless
# CL_DOCKER_GL1_JAVA_OPTS is used (compose gives gl1 its own override slot).
[ -n "$NODE_JAVA_OPTS" ] && ENV_PROFILE_BLOCK="$ENV_PROFILE_BLOCK
CL_DOCKER_JAVA_OPTS=$NODE_JAVA_OPTS"

cp "$PROJECT_ROOT/docker/docker-compose.yaml" "$STAGING/docker-compose.yaml"
# The entrypoint is bind-mounted over the image's baked copy so the guard/JVM logic
# always matches THIS tooling (which writes the .env the entrypoint interprets) —
# registry images built from other branches (e.g. release/testnet) may carry an older
# entrypoint. Volume lists merge across compose -f files, so this appends to the base.
cat > "$STAGING/docker-compose.host.yaml" <<'EOF'
services:
  gl0:
    network_mode: host
    volumes:
      - ./entrypoint.sh:/tessellation/entrypoint.sh:ro
  gl1:
    network_mode: host
    volumes:
      - ./entrypoint.sh:/tessellation/entrypoint.sh:ro
EOF

mkdir -p "$STAGING/node0"
cp "$PROJECT_ROOT/.github/config/genesis.csv" "$STAGING/node0/genesis.csv"
[ -n "$NODE0_ADDRESS" ] && printf '\n%s,100000000000000\n' "$NODE0_ADDRESS" >> "$STAGING/node0/genesis.csv"

for i in $(seq 0 $((NUM_NODES - 1))); do
  mkdir -p "$STAGING/node$i"
  # PRESET_KEYS: the on-node key.p12 stays as-is; never stage/overwrite it.
  [ "$PRESET_KEYS" = "true" ] || cp "$NODE_KEYS_DIR/$i/key.p12" "$STAGING/node$i/key.p12"

  cat > "$STAGING/node$i/.env" <<ENVEOF
TESSELLATION_DOCKER_VERSION=${ENV_DOCKER_VERSION}
CL_DOCKER_CORE_IMAGE=${ENV_CORE_IMAGE}
CL_APP_ENV=${DEPLOY_APP_ENV}
CL_COLLATERAL=0
${ENV_PROFILE_BLOCK}

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
    # Existing chain data lives at gl0-data/snapshot (genesis deployments) OR only at
    # gl0-data/incremental_snapshot (tn1-3 layout, snapshot-path remapped) — probe both,
    # otherwise migrated data would fall through to a catastrophic run-genesis.
    HAS_DATA=$(ssh "${NODES[0]}" "{ test -f $DIR/gl0-data/snapshot/ordinal/0/0 || test -d $DIR/gl0-data/incremental_snapshot/ordinal; } && echo yes || echo no" 2>/dev/null || echo "no")
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
  scp -q "$STAGING/docker-compose.yaml" "$STAGING/docker-compose.host.yaml" "$PROJECT_ROOT/docker/entrypoint.sh" "$h:$DIR/"
  # scp's sftp backend drops the exec bit; the image ENTRYPOINT execs this file directly
  ssh "$h" "chmod +x $DIR/entrypoint.sh"
  scp -q "$STAGING/node$i/.env" "$h:$DIR/"
    # PRESET_KEYS: leave the on-node key.p12 untouched (nothing staged to ship).
    [ "$PRESET_KEYS" = "true" ] || scp -q "$STAGING/node$i/key.p12" "$h:$DIR/"
  [ "${SEEDLIST_SHIP:-false}" = "true" ] && scp -q "$STAGING/seedlist" "$h:$DIR/"
  [ "$i" -eq 0 ] && scp -q "$STAGING/node0/genesis.csv" "$h:$DIR/"
  if [ "$IMAGE_SOURCE" = "registry" ]; then
  log "  Pulling image $IMAGE"
  ssh "$h" "docker pull $IMAGE" 2>&1 | grep -v "^$" || true
  else
  log "  Transferring image"
  scp -q "$IMAGE_TAR" "$h:$DIR/"
  log "  Loading image"
  ssh "$h" "docker load < $DIR/tessellation-nightly.tar.gz" 2>&1 | grep -v "^$"
  fi
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
# SKIP_TX_SENDER=true for migration/rehearsal deploys — its test keys hold no balance
# on the real chain, so it would only flood rejected transactions.
TX_SENDER_JAR="$PROJECT_ROOT/docker/jars/tools.jar"
TX_SENDER_CONF="$PROJECT_ROOT/docker/config/tx-sender.conf"
if [ "${SKIP_TX_SENDER:-false}" != "true" ] && [ -f "$TX_SENDER_JAR" ] && [ -s "$TX_SENDER_JAR" ] && [ -f "$TX_SENDER_CONF" ]; then
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

fi  # end node phases (skipped when SKIP_NODES=true)

# === Phase 6: Snapshot-streaming on 4th node ===
if [ -n "$SS_NODE" ]; then
  log "Setting up snapshot-streaming on $SS_NODE"
  SS_DIR="$PROJECT_ROOT/docker/snapshot-streaming"
  SS_REMOTE_DIR="$DIR/snapshot-streaming"

  # SS database. Default = the local postgres container this deploy manages on the
  # streaming node. Point SS_DB_URL at an external postgres (e.g. the block-explorer
  # DB on AWS) and the deploy skips the local postgres container entirely; SS,
  # prisma, and the preserve/seed psql helpers all use the URL. Plain form only:
  # postgresql://user:password@host:port/db — no query params, no spaces.
  SS_DB_URL="${SS_DB_URL:-postgresql://snapshot_streaming:snapshot_streaming@127.0.0.1:5432/snapshot_streaming}"
  read -r SS_DB_HOST SS_DB_PORT SS_DB_USER SS_DB_PASS SS_DB_NAME <<< "$(python3 - "$SS_DB_URL" <<'PYEOF'
import sys
from urllib.parse import urlsplit, unquote
u = urlsplit(sys.argv[1])
print(u.hostname or "127.0.0.1", u.port or 5432, unquote(u.username or ""), unquote(u.password or ""), u.path.lstrip("/"))
PYEOF
)"
  case "$SS_DB_HOST" in
    127.0.0.1|localhost) SS_DB_LOCAL=true ;;
    *) SS_DB_LOCAL=false; log "  SS database: external at $SS_DB_HOST:$SS_DB_PORT/$SS_DB_NAME (local postgres skipped)" ;;
  esac

  # Re-deploys: the SS/block-explorer containers run as root and leave root-owned files under
  # $SS_REMOTE_DIR (bind mounts) that the login user can't overwrite/rm. Take ownership first.
  ssh "$SS_NODE" "sudo mkdir -p $SS_REMOTE_DIR && sudo chown -R \$(id -un):\$(id -gn) $SS_REMOTE_DIR" 2>/dev/null || true

  # Build/obtain JAR — build-snapshot-streaming.sh handles reuse logic internally.
  # Registry mode pulls the SS image, so skip the jar build (block_explorer is still cloned).
  [ "$IMAGE_SOURCE" = "registry" ] && export SKIP_SS_JAR=true
  # External SS DB: schema is owned by the existing block-explorer deployment — no
  # local BE app, no prisma, no clone needed.
  [ "$SS_DB_LOCAL" = "false" ] && export SKIP_BE_CLONE=true
  source "$SS_DIR/build-snapshot-streaming.sh"
  show_time "Snapshot-streaming build"

  # Generate application.conf for remote (host networking: postgres on localhost, GL0 on genesis IP)
  SS_CONFIG="$STAGING/ss-application.conf"
  # Build l0Peers from ALL nodes so snapshot-streaming verifies against a majority and
  # spreads pull load — a single peer gets 429-rate-limited during bulk catch-up.
  SS_L0_PEERS=""
  for i in $(seq 0 $((NUM_NODES - 1))); do
    _pid="${PEER_IDS[$i]}"
    [ "$i" -eq 0 ] && _sep="" || _sep=", "
    SS_L0_PEERS="${SS_L0_PEERS}${_sep}{ id = \"$_pid\", ip = \"${NODE_IPS[$i]}\", port = 9000 }"
  done
  cat > "$SS_CONFIG" <<'STATICEOF'
include classpath("application")
STATICEOF
  cat >> "$SS_CONFIG" <<CONFEOF
snapshotStreaming {
  environment = dev
  lastIncrementalSnapshotPath = "/app/data/seed-snapshot.json.gz"
  node {
    l0Peers = [$SS_L0_PEERS]
    pullInterval = 5s
    pullLimit = 10
  }
  db {
    host = "$SS_DB_HOST"
    port = $SS_DB_PORT
    user = "$SS_DB_USER"
    password = "$SS_DB_PASS"
    database = "$SS_DB_NAME"
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
    restart: unless-stopped
    # Bind Postgres to loopback only — with host networking it would otherwise listen on
    # 0.0.0.0:5432. The SS app and block-explorer connect via 127.0.0.1, so loopback suffices.
    command: ["postgres", "-c", "listen_addresses=127.0.0.1"]
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
    restart: unless-stopped
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
  # NOTE: no block-explorer service — the BE app runs on existing external infra.
  # Only its prisma schema dir is shipped (local-postgres mode) to manage the DB schema.

volumes:
  ss-pgdata:
    name: ss-pgdata
DCEOF

  # Inject the (possibly external) DB URL; with an external DB also drop the local
  # postgres service and the depends_on gates that reference it.
  python3 - "$SS_COMPOSE" "$SS_DB_URL" "$SS_DB_LOCAL" <<'PYEOF'
import sys
path, url, local = sys.argv[1], sys.argv[2], sys.argv[3] == "true"
default_url = "postgresql://snapshot_streaming:snapshot_streaming@127.0.0.1:5432/snapshot_streaming"
drop_services = {"snapshot-streaming-postgres:"}
out, skip_service, skip_depends = [], False, 0
for line in open(path):
    s = line.rstrip("\n").replace(default_url, url)
    indent = len(s) - len(s.lstrip())
    if not local:
        if s.strip() in drop_services and indent == 2:
            skip_service = True
            continue
        if skip_service:
            if s.strip() and indent <= 2:
                skip_service = False  # next top-level key; fall through
            else:
                continue
        if s.strip() == "depends_on:":
            skip_depends = indent
            continue
        if skip_depends:
            if s.strip() and indent > skip_depends:
                continue
            skip_depends = 0
    out.append(s)
open(path, "w").write("\n".join(out) + "\n")
PYEOF

  # Registry mode: point the SS service at the prebuilt GHCR image (jar baked in, ENTRYPOINT
  # set) instead of eclipse-temurin + a mounted jar — drop the command and the jar volume.
  if [ "$IMAGE_SOURCE" = "registry" ]; then
    : "${CL_DOCKER_SS_IMAGE:?IMAGE_SOURCE=registry requires CL_DOCKER_SS_IMAGE}"
    SS_IMAGE="${CL_DOCKER_SS_IMAGE}:${TESSELLATION_DOCKER_VERSION}"
    log "  snapshot-streaming image source=registry ($SS_IMAGE)"
    python3 - "$SS_COMPOSE" "$SS_IMAGE" <<'PYEOF'
import sys
path, img = sys.argv[1], sys.argv[2]
out = []
for line in open(path):
    s = line.rstrip("\n")
    if s.strip() == "image: eclipse-temurin:21-jre":
        out.append(s.replace("eclipse-temurin:21-jre", img))
    elif "org.constellation.snapshotstreaming.App" in s:
        continue  # baked ENTRYPOINT in the image
    elif "snapshot-streaming.jar:/app/snapshot-streaming.jar" in s:
        continue  # jar baked into the image
    else:
        out.append(s)
open(path, "w").write("\n".join(out) + "\n")
PYEOF
  fi

  # Transfer to SS node
  log "  Transferring to $SS_NODE"
  ssh "$SS_NODE" "mkdir -p $SS_REMOTE_DIR/data"
  [ "$IMAGE_SOURCE" = "registry" ] || scp -q "$SS_DIR/snapshot-streaming.jar" "$SS_NODE:$SS_REMOTE_DIR/"
  scp -q "$SS_CONFIG" "$SS_NODE:$SS_REMOTE_DIR/application.conf"
  scp -q "$SS_COMPOSE" "$SS_NODE:$SS_REMOTE_DIR/docker-compose.yaml"

  # Start postgres
  if [ "$SS_DB_LOCAL" = "true" ]; then
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
  fi

  if [ "$SS_DB_LOCAL" = "true" ]; then
    # Ship ONLY block_explorer's prisma dir — used to manage the local postgres schema.
    # The BE app itself never runs on-cluster (it lives on existing external infra).
    log "  Transferring block_explorer prisma schema to $SS_NODE"
    BE_DIR="$SS_DIR/block-explorer"
    # sudo: prior deploys' prisma ran in a root docker container and left root-owned
    # files under block-explorer (bind mount), which the login user can't rm on redeploy.
    ssh "$SS_NODE" "sudo rm -rf $SS_REMOTE_DIR/block-explorer && mkdir -p $SS_REMOTE_DIR/block-explorer"
    scp -rq "$BE_DIR/prisma" "$SS_NODE:$SS_REMOTE_DIR/block-explorer/prisma"
  fi

  # Stop SS (and any legacy block-explorer container from older deploys) before touching
  # the DB or resume state; SS restarts below after schema + seed are settled.
  ssh "$SS_NODE" "if [ -f $SS_REMOTE_DIR/docker-compose.yaml ]; then cd $SS_REMOTE_DIR && docker compose stop snapshot-streaming 2>/dev/null; fi; docker rm -f block-explorer 2>/dev/null" || true

  # Apply schema. Existing SS data is PRESERVED by default — the destructive reset
  # (drop schema + force-reset + re-seed from head) only runs when the DB is
  # empty/uninitialized or explicitly requested via SS_RESET_DB=true.
  # psql via a throwaway container against the SS DB (local container or external
  # SS_DB_URL; host networking so 127.0.0.1 resolves to the node). Pass connection
  # params as libpq PG* env vars rather than a positional URL, so the password stays
  # out of the psql connection string / logs — matching the prisma `-e DATABASE_URL`
  # calls below.
  SS_PSQL="docker run --rm --network host -e PGHOST=$SS_DB_HOST -e PGPORT=$SS_DB_PORT -e PGUSER=$SS_DB_USER -e PGPASSWORD=$SS_DB_PASS -e PGDATABASE=$SS_DB_NAME postgres:15-alpine psql"
  row_count=$(ssh "$SS_NODE" "$SS_PSQL -t -A -c 'SELECT COUNT(*) FROM global_snapshots;'" 2>/dev/null || echo "")
  SS_PRESERVE=false
  [ "${SS_RESET_DB:-false}" != "true" ] && [ "$row_count" -gt 0 ] 2>/dev/null && SS_PRESERVE=true

  if [ "$SS_PRESERVE" = "true" ]; then
    log "  Existing SS data ($row_count rows) — preserving (SS_RESET_DB=true to wipe)"
    if [ "$SS_DB_LOCAL" = "true" ]; then
      # Incremental, non-destructive schema sync: fails loudly on drift that would
      # lose data instead of silently wiping.
      push_out=$(ssh "$SS_NODE" "docker run --rm --network host -v $SS_REMOTE_DIR/block-explorer/prisma:/app/prisma -w /app -e DATABASE_URL='$SS_DB_URL' node:20-alpine sh -c 'npx prisma@6.2.1 db push'" 2>&1) || {
        printf '%s\n' "$push_out" | tail -5
        log "ERROR: incremental schema sync failed (block-explorer schema drift requires data loss?). Re-run with SS_RESET_DB=true to wipe and reset."
        exit 1
      }
      printf '%s\n' "$push_out" | tail -2
    else
      log "  External DB — schema owned by the block-explorer deployment; skipping prisma"
    fi
  elif [ "$SS_DB_LOCAL" = "true" ]; then
    log "  Applying database schema (reset)"
    ssh "$SS_NODE" "$SS_PSQL -c 'DROP SCHEMA public CASCADE; CREATE SCHEMA public;'" 2>/dev/null || true
    ssh "$SS_NODE" "docker run --rm --network host -v $SS_REMOTE_DIR/block-explorer/prisma:/app/prisma -w /app -e DATABASE_URL='$SS_DB_URL' node:20-alpine sh -c 'npx prisma@6.2.1 db push --accept-data-loss --force-reset'" 2>&1 | tail -3
  else
    # Never reset an external DB — its schema/data are owned by the block-explorer
    # deployment. SS will simply seed from head and insert forward.
    log "  External DB (rows: ${row_count:-none}) — schema untouched; SS seeds from head"
  fi

  # Wait for GL0 before seed/trim decisions. ALL chain probes in this phase run FROM
  # the SS node (ssh), not this machine: the SS node is in the snapshot allowlist and
  # inside the cluster's firewall scope, while the orchestrating laptop's IP is
  # neither stable nor allowlisted (bitten live: rotated egress IP -> ufw-blocked
  # curls -> silent set -e death in the trim loop).
  gl0_url="http://${NODE_IPS[0]}:9000"
  for attempt in $(seq 1 60); do
    latest_ord=$(ssh "$SS_NODE" "curl -sf --max-time 5 '$gl0_url/global-snapshots/latest/ordinal'" 2>/dev/null | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('value',d) if isinstance(d,dict) else d)" 2>/dev/null || echo 0)
    [ "$latest_ord" -ge 2 ] 2>/dev/null && break
    sleep 3
  done

  # Fetch the chain hash for an ordinal, falling back across ALL nodes — node0 also
  # serves the tx-sender, so its public-route rate-limit bucket can be transiently
  # drained; the other validators answer the same question.
  gl0_hash_at() {
    local ord="$1" ip out attempt
    for ip in "${NODE_IPS[@]}"; do
      for attempt in 1 2; do
        out=$(ssh "$SS_NODE" "curl -sf --max-time 5 'http://$ip:9000/global-snapshots/$ord/hash'" 2>/dev/null \
          | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('value',d) if isinstance(d,dict) else d)" 2>/dev/null | tr -d '"')
        if [ -n "$out" ]; then printf '%s' "$out"; return 0; fi
        sleep 1
      done
    done
    return 1
  }

  NEED_SEED=true
  if [ "$SS_PRESERVE" = "true" ]; then
    # A rollback restart re-forges the tip: global_snapshots.ordinal is UNIQUE but SS
    # inserts are ON CONFLICT (hash) DO NOTHING, so a stale forked row at a re-forged
    # ordinal would crash SS. Trim from the tip until the DB hash matches the chain
    # (FKs cascade on delete).
    trimmed=0
    while :; do
      db_tip=$(ssh "$SS_NODE" "$SS_PSQL -t -A -c \"SELECT ordinal||' '||hash FROM global_snapshots ORDER BY ordinal DESC LIMIT 1;\"" 2>/dev/null || echo "")
      [ -n "$db_tip" ] || break
      db_ord="${db_tip%% *}"; db_hash="${db_tip#* }"
      # set -e safe: on probe failure fall into the loud guard below, don't die silently
      chain_hash=$(gl0_hash_at "$db_ord") || chain_hash=""
      if [ -z "$chain_hash" ]; then
        log "ERROR: cannot fetch chain hash for ordinal $db_ord from GL0 — refusing to trim blindly. Retry, or SS_RESET_DB=true."
        exit 1
      fi
      [ "$chain_hash" = "$db_hash" ] && break
      if [ "$trimmed" -ge 100 ]; then
        log "ERROR: SS DB diverges from the chain by >100 snapshots at the tip — different chain? Re-run with SS_RESET_DB=true."
        exit 1
      fi
      ssh "$SS_NODE" "$SS_PSQL -c 'DELETE FROM global_snapshots WHERE ordinal >= $db_ord;'" >/dev/null
      trimmed=$((trimmed + 1))
    done
    [ "$trimmed" -gt 0 ] && log "  Trimmed $trimmed forked tip row(s) from SS DB"

    # Keep the resume state file if it still matches the chain — SS then resumes
    # exactly where it left off and backfills with no gap. Otherwise fall through
    # to re-seeding from head (leaves a gap in DB history, logged below).
    ss_state=$(ssh "$SS_NODE" "zcat $SS_REMOTE_DIR/data/seed-snapshot.json.gz 2>/dev/null" \
      | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['snapshot']['signed']['value']['ordinal'],d['snapshot']['hash'])" 2>/dev/null || echo "")
    if [ -n "$ss_state" ]; then
      st_ord="${ss_state%% *}"; st_hash="${ss_state#* }"
      if [ "$(gl0_hash_at "$st_ord")" = "$st_hash" ]; then
        NEED_SEED=false
        log "  Resume state valid (ordinal $st_ord) — SS resumes from there, no gap"
      else
        log "  Resume state at ordinal $st_ord no longer matches the chain (rollback fork) — re-seeding from head"
      fi
    else
      log "  No readable resume state file — re-seeding from head"
    fi
  fi

  if [ "$NEED_SEED" = "true" ]; then
    # Seed with initial snapshot from GL0
    log "  Seeding from GL0"
    combined_json=$(ssh "$SS_NODE" "curl -sf --max-time 30 '$gl0_url/global-snapshots/latest/combined'")
    seed_ordinal=$(echo "$combined_json" | python3 -c "import sys,json;print(json.load(sys.stdin)[0]['value']['ordinal'])")
    snapshot_hash=$(gl0_hash_at "$seed_ordinal")
    proofs_hash=$(echo "$combined_json" | python3 -c "import sys,json;print(json.dumps(json.load(sys.stdin)[0]['proofs'],sort_keys=True,separators=(',',':')))" | shasum -a 256 | awk '{print $1}')
    log "  Seed ordinal: $seed_ordinal, hash: ${snapshot_hash:0:16}..."

    echo "$combined_json" | python3 -c "
import sys,json
d=json.load(sys.stdin)
print(json.dumps({'snapshot':{'signed':d[0],'hash':'$snapshot_hash','proofsHash':'$proofs_hash'},'state':d[1]}))" \
      | gzip | ssh "$SS_NODE" "sudo tee $SS_REMOTE_DIR/data/seed-snapshot.json.gz >/dev/null"

    if [ "$SS_PRESERVE" = "true" ]; then
      db_max=$(ssh "$SS_NODE" "$SS_PSQL -t -A -c 'SELECT COALESCE(MAX(ordinal),-1) FROM global_snapshots;'" 2>/dev/null || echo "-1")
      gap=$((seed_ordinal - db_max - 1))
      [ "$gap" -gt 0 ] 2>/dev/null && log "  NOTE: history gap of $gap snapshots (ordinals $((db_max + 1))..$((seed_ordinal - 1))) will remain in the SS DB — SS only pulls forward from the seed"
    fi
  fi

  # Start snapshot-streaming
  log "  Starting snapshot-streaming"
  ssh "$SS_NODE" "cd $SS_REMOTE_DIR && docker compose up -d snapshot-streaming"

  # Wait for indexing to start
  for attempt in $(seq 1 30); do
    count=$(ssh "$SS_NODE" "$SS_PSQL -t -A -c 'SELECT COUNT(*) FROM global_snapshots;'" 2>/dev/null || echo 0)
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
