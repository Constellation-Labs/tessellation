#!/usr/bin/env bash
# Deploy monitoring stack (Prometheus + Grafana + ClickHouse) to the nightly cluster.
#
# Deploys to the last node in REMOTE_NODES (n3).
# ClickHouse runs on the same node — tessellation nodes connect via HTTP.
#
# Expected env vars:
#   REMOTE_NODES              - comma-separated SSH aliases (e.g. n0,n1,n2,n3)
#   GRAFANA_ADMIN_PASSWORD    - Grafana admin password (default: admin)
#   CLICKHOUSE_PASSWORD       - ClickHouse default user password (default: clickhouse)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REMOTE_DIR="/opt/monitoring"

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

# --- Prometheus config with real IPs ---
sed -e "s/\${NODE_IP_0}/${NODE_IPS[0]}/g" \
    -e "s/\${NODE_IP_1}/${NODE_IPS[1]}/g" \
    -e "s/\${NODE_IP_2}/${NODE_IPS[2]}/g" \
    "$SCRIPT_DIR/prometheus/prometheus.yaml" \
  | ssh "$SERVER_NODE" "cat > $REMOTE_DIR/prometheus/prometheus.yaml"

# --- Grafana config ---
scp -q "$SCRIPT_DIR/grafana/grafana.ini" "$SERVER_NODE:$REMOTE_DIR/grafana/grafana.ini"

# Datasources: Prometheus + local ClickHouse (both on localhost via host networking)
cat <<EOF | ssh "$SERVER_NODE" "cat > $REMOTE_DIR/grafana/provisioning/datasources/datasources.yaml"
apiVersion: 1
datasources:
  - name: prometheus
    uid: prometheus
    type: prometheus
    access: proxy
    url: http://localhost:9090
    isDefault: true
    editable: true

  - name: ClickHouse
    uid: clickhouse
    type: grafana-clickhouse-datasource
    access: proxy
    isDefault: false
    editable: true
    jsonData:
      host: localhost
      port: 8123
      protocol: http
      secure: false
      username: default
      defaultDatabase: default
    secureJsonData:
      password: ${CH_PASS}
EOF

# Dashboards
scp -q "$SCRIPT_DIR/grafana/provisioning/dashboards/dashboards.yaml" \
       "$SCRIPT_DIR/grafana/provisioning/dashboards/tessellation.json" \
       "$SCRIPT_DIR/grafana/provisioning/dashboards/jvm-micrometer.json" \
       "$SERVER_NODE:$REMOTE_DIR/grafana/provisioning/dashboards/"

# --- .env ---
ssh "$SERVER_NODE" "cat > $REMOTE_DIR/.env" <<EOF
GRAFANA_ADMIN_PASSWORD=${GRAFANA_ADMIN_PASSWORD:-admin}
CLICKHOUSE_PASSWORD=${CH_PASS}
EOF

# --- Start services ---
log "Pulling images and starting services"
ssh "$SERVER_NODE" "cd $REMOTE_DIR && docker compose pull -q && docker compose up -d"

# --- Wait for ClickHouse to be ready ---
log "Waiting for ClickHouse"
ch_ready=false
for i in $(seq 1 30); do
  if ssh "$SERVER_NODE" "docker exec clickhouse clickhouse-client --password '$CH_PASS' -q 'SELECT 1'" >/dev/null 2>&1; then
    log "  ClickHouse ready"
    ch_ready=true
    break
  fi
  sleep 2
done
if [ "$ch_ready" != "true" ]; then
  log "ERROR: ClickHouse failed to start within 60 seconds"
  exit 1
fi

# --- Initialize ClickHouse tables (idempotent) ---
log "Initializing ClickHouse tables"
ssh "$SERVER_NODE" "docker exec -i clickhouse clickhouse-client --password '$CH_PASS'" \
  < "$SCRIPT_DIR/clickhouse/init.sql" 2>&1

# --- Deploy network_process_exporter on each tessellation node ---
# Custom eBPF (BCC) exporter that emits per-PID TCP/UDP send/recv byte counters
# on :9435. Runs as a host systemd unit (eBPF kprobes need host kernel access;
# running this in a container would require --privileged + kernel-header mounts).
# BCC compiles BPF at runtime against kernel headers, so we install headers
# matching the currently-booted kernel. If a node is running a deprecated kernel
# whose headers are no longer in apt, the install fails and the service stays
# down — reboot the node onto a current kernel and re-run this script.
# Prometheus scrapes these via the network-process-exporter job in prometheus.yaml.
for h in "${NODES[@]}"; do
  log "Deploying network_process_exporter on $h"
  ssh "$h" "DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
              bpfcc-tools python3-bpfcc python3-prometheus-client \
              linux-headers-\$(uname -r) >/dev/null" \
    || { log "  $h: header/BCC install failed (running kernel may need a reboot); skipping service start"; continue; }
  scp -q "$SCRIPT_DIR/network-process-exporter/network_process_exporter.py" \
         "$h:/usr/local/bin/network_process_exporter.py"
  ssh "$h" "chmod +x /usr/local/bin/network_process_exporter.py"
  scp -q "$SCRIPT_DIR/network-process-exporter/network-process-exporter.service" \
         "$h:/etc/systemd/system/network-process-exporter.service"
  ssh "$h" "systemctl daemon-reload && systemctl enable --now network-process-exporter.service" \
    && log "  $h: network-process-exporter running on :9435" \
    || log "  $h: network-process-exporter failed to start (non-fatal)"
done

log "Monitoring deployed on $SERVER_NODE"
log "  Prometheus:               http://$SERVER_IP:9090"
log "  Grafana:                  http://$SERVER_IP:3000"
log "  ClickHouse:               $SERVER_IP:8123 (HTTP)"
log "  process-exporter:         ${NODES[*]} on :9256"
log "  network-process-exporter: ${NODES[*]} on :9435"
log ""
log "To enable ClickHouse logging on tessellation nodes, redeploy with:"
log "  CLICKHOUSE_HOST=$SERVER_IP CLICKHOUSE_PORT=8123 CLICKHOUSE_PROTOCOL=http CLICKHOUSE_PASSWORD=<secret>"
