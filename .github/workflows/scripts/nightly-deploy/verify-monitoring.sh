#!/usr/bin/env bash
# Verify the monitoring pipeline (Prometheus, Grafana, ClickHouse log
# ingestion) on the last NIGHTLY_HOSTS node. Fails the workflow if no
# log rows arrive within 2 minutes.
#
# Env:
#   NIGHTLY_HOSTS — comma-separated host IPs (monitoring runs on the last)
#   CH_PASS       — ClickHouse password

set -uo pipefail

IFS=',' read -ra IPS <<< "$NIGHTLY_HOSTS"
LAST=$((${#IPS[@]} - 1))

# Prometheus/Grafana listen on host networking on the monitoring node, not
# exposed publicly — probe via SSH from inside the node.
echo "=== Prometheus ==="
ssh "n$LAST" "curl -fsS http://localhost:9090/-/ready"

echo "=== Grafana ==="
ssh "n$LAST" "curl -fsS http://localhost:3000/api/health" | python3 -c "
import sys,json
h=json.load(sys.stdin)
assert h.get('database') == 'ok', f'Grafana unhealthy: {h}'
print(f'  version: {h.get(\"version\")}')
"

echo "=== ClickHouse log ingestion ==="
# Nodes log asynchronously; allow up to 2 min for the first batch to land.
for i in $(seq 1 12); do
  rows=$(ssh "n$LAST" "docker exec clickhouse clickhouse-client --password '$CH_PASS' -q \"SELECT count() FROM default.nightly_logs WHERE timestamp > now() - INTERVAL 2 MINUTE\"" 2>/dev/null || echo 0)
  if [ "${rows:-0}" -gt 0 ] 2>/dev/null; then
    echo "  OK: $rows rows in last 2min"
    exit 0
  fi
  printf "  Waiting for log rows... (%d/12)\n" "$i"
  sleep 10
done

echo "::error::No rows ingested into nightly_logs within 2 minutes"
echo "=== Diagnostics ==="
echo "--- n0 /opt/tessellation/.env CLICKHOUSE_* lines ---"
ssh n0 "grep ^CLICKHOUSE_ /opt/tessellation/.env 2>&1 || echo '(no .env or no CLICKHOUSE_ lines)'"
echo "--- ClickHouse server reachable from n0? ---"
ssh n0 "curl -sS -o /dev/null -w 'http_code=%{http_code}\n' --max-time 5 http://\$(grep ^CLICKHOUSE_HOST= /opt/tessellation/.env | cut -d= -f2):8123/ping || true"
echo "--- Total rows in nightly_logs (all time) ---"
ssh "n$LAST" "docker exec clickhouse clickhouse-client --password '$CH_PASS' -q 'SELECT count() FROM default.nightly_logs'" || true
echo "--- gl0 recent log tail on n0 ---"
ssh n0 "docker logs gl0 2>&1 | tail -30" || true
exit 1
