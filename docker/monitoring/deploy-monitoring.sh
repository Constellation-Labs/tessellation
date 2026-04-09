#!/usr/bin/env bash
# Deploy monitoring stack (Prometheus + Grafana + ClickHouse) to the nightly cluster.
#
# Deploys to the last node in REMOTE_NODES (n3).
# ClickHouse runs on the same node — tessellation nodes connect via HTTP.
#
# Behavior:
#   - Always transfers/updates config files (Prometheus targets, dashboards, etc.)
#   - Only starts Grafana/Prometheus/ClickHouse if they are NOT already running
#   - Updates tessellation node .env files with ClickHouse connection settings
#
# Expected env vars:
#   REMOTE_NODES              - comma-separated SSH aliases (e.g. n0,n1,n2,n3)
#   GRAFANA_ADMIN_PASSWORD    - Grafana admin password (default: admin)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REMOTE_DIR="/opt/monitoring"
TESS_DIR="/opt/tessellation"

IFS=',' read -ra ALL_NODES <<< "$REMOTE_NODES"
NODES=("${ALL_NODES[@]:0:3}")
SERVER_NODE="${ALL_NODES[3]:-${ALL_NODES[2]}}"

# Resolve IPs from SSH config (same pattern as remote-deploy.sh)
NODE_IPS=()
for h in "${NODES[@]}"; do
  NODE_IPS+=($(ssh -G "$h" | awk '/^hostname / {print $2}'))
done
SERVER_IP=$(ssh -G "$SERVER_NODE" | awk '/^hostname / {print $2}')

log() { printf "\033[34m[monitoring]\033[0m %s\n" "$*"; }

CH_PASS="${CLICKHOUSE_PASSWORD:-clickhouse}"

log "Deploying to $SERVER_NODE ($SERVER_IP)"

# --- Create directories ---
ssh "$SERVER_NODE" "mkdir -p $REMOTE_DIR/{prometheus,grafana/provisioning/datasources,grafana/provisioning/dashboards,clickhouse}"

# --- Transfer compose file ---
scp -q "$SCRIPT_DIR/docker-compose.remote.yaml" "$SERVER_NODE:$REMOTE_DIR/docker-compose.yaml"

# --- ClickHouse config ---
scp -q "$SCRIPT_DIR/clickhouse/init.sql" \
       "$SCRIPT_DIR/clickhouse/enable_json.xml" \
       "$SERVER_NODE:$REMOTE_DIR/clickhouse/"
# HTTPS is not used — ClickHouse serves HTTP-only on port 8123 (host networking)

# --- Prometheus config with real IPs ---
sed -e "s/\${NODE_IP_0}/${NODE_IPS[0]}/g" \
    -e "s/\${NODE_IP_1}/${NODE_IPS[1]}/g" \
    -e "s/\${NODE_IP_2}/${NODE_IPS[2]}/g" \
    "$SCRIPT_DIR/prometheus/prometheus.yaml" \
  | ssh "$SERVER_NODE" "cat > $REMOTE_DIR/prometheus/prometheus.yaml"

# --- Grafana config ---
scp -q "$SCRIPT_DIR/grafana/grafana.ini" "$SERVER_NODE:$REMOTE_DIR/grafana/grafana.ini"

# Datasources (localhost — Grafana, Prometheus, and ClickHouse all share the host network)
scp -q "$SCRIPT_DIR/grafana/provisioning/datasources/datasources.yaml" \
       "$SERVER_NODE:$REMOTE_DIR/grafana/provisioning/datasources/"

# Dashboards
scp -q "$SCRIPT_DIR/grafana/provisioning/dashboards/dashboards.yaml" \
       "$SCRIPT_DIR/grafana/provisioning/dashboards/tessellation.json" \
       "$SCRIPT_DIR/grafana/provisioning/dashboards/jvm-micrometer.json" \
       "$SERVER_NODE:$REMOTE_DIR/grafana/provisioning/dashboards/"

# --- .env ---
ssh "$SERVER_NODE" "echo 'GRAFANA_ADMIN_PASSWORD=${GRAFANA_ADMIN_PASSWORD:-admin}' > $REMOTE_DIR/.env"

# --- Check if monitoring services are already running ---
ALL_RUNNING=true
for svc in clickhouse prometheus grafana; do
  if ssh "$SERVER_NODE" "docker inspect --format='{{.State.Running}}' $svc 2>/dev/null" | grep -q "true"; then
    log "  $svc: running"
  else
    log "  $svc: not running"
    ALL_RUNNING=false
  fi
done

if [ "$ALL_RUNNING" = "true" ]; then
  log "All monitoring services already running — reloading configs"
  # Prometheus supports config reload via SIGHUP
  ssh "$SERVER_NODE" "docker kill --signal=SIGHUP prometheus" 2>/dev/null || true
  # Grafana auto-reloads provisioned dashboards on file change
else
  log "Starting monitoring services"
  ssh "$SERVER_NODE" "cd $REMOTE_DIR && docker compose pull -q && docker compose up -d"

  # --- Wait for ClickHouse to be ready ---
  log "Waiting for ClickHouse"
  CLICKHOUSE_READY=false
  for i in $(seq 1 30); do
    if ssh "$SERVER_NODE" "docker exec clickhouse clickhouse-client --password clickhouse -q 'SELECT 1'" >/dev/null 2>&1; then
      log "  ClickHouse ready"
      CLICKHOUSE_READY=true
      break
    fi
    sleep 2
  done
  if [ "$CLICKHOUSE_READY" != "true" ]; then
    log "ERROR: ClickHouse did not become ready within 60 seconds"
    exit 1
  fi

  # --- Initialize ClickHouse tables (idempotent) ---
  log "Initializing ClickHouse tables"
  ssh "$SERVER_NODE" "docker exec -i clickhouse clickhouse-client --password clickhouse" \
    < "$SCRIPT_DIR/clickhouse/init.sql"
fi

# --- Update tessellation node configs with ClickHouse settings ---
log "Updating tessellation node configs"
for h in "${NODES[@]}"; do
  if ssh "$h" "test -f $TESS_DIR/.env" 2>/dev/null; then
    ssh "$h" "sed -i '/^CLICKHOUSE_/d' $TESS_DIR/.env"
    printf '%s\n' \
      "CLICKHOUSE_HOST=${SERVER_IP}" \
      "CLICKHOUSE_USER=default" \
      "CLICKHOUSE_PASSWORD=clickhouse" \
      "CLICKHOUSE_PORT=8123" \
      "CLICKHOUSE_DATABASE=default" \
      "CLICKHOUSE_PROTOCOL=http" \
      "CLICKHOUSE_LOGS_TABLE_NAME=nightly_logs" \
    | ssh "$h" "cat >> $TESS_DIR/.env"
    log "  $h: updated"
  else
    log "  $h: no .env found, skipping"
  fi
done

log "Monitoring deployed on $SERVER_NODE"
log "  Prometheus:  http://$SERVER_IP:9090"
log "  Grafana:     http://$SERVER_IP:3000"
log "  ClickHouse:  $SERVER_IP:8123 (HTTP)"
