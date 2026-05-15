#!/usr/bin/env bash
# Smoke-test the deployed cluster: block_explorer API (if present), GL0/GL1
# cluster peering, and global-snapshot production. Run after deploy.
#
# Env:
#   NIGHTLY_HOSTS — comma-separated host IPs (n0 first)

set -uo pipefail

IFS=',' read -ra IPS <<< "$NIGHTLY_HOSTS"
GENESIS_IP="${IPS[0]}"

# block-explorer is only deployed when there's a dedicated SS node
# (NIGHTLY_HOSTS has >=4 entries; remote-deploy.sh uses the 4th as SS_NODE).
if [ "${#IPS[@]}" -ge 4 ]; then
  BE_NODE_IDX=$((${#IPS[@]} - 1))
  BE_IP="${IPS[$BE_NODE_IDX]}"
  echo "=== Block Explorer (n$BE_NODE_IDX) ==="
  # serverless-offline boots after `npm install`, which can take a couple of minutes
  # on the first deploy. Poll for up to 4 min before giving up.
  for i in $(seq 1 24); do
    if curl -fsS -o /dev/null --max-time 5 "http://$BE_IP:3001/global-snapshots/latest"; then
      echo "  OK: block-explorer responding on $BE_IP:3001"
      break
    fi
    printf "  Waiting for block-explorer... (%d/24)\n" "$i"
    if [ "$i" -eq 24 ]; then
      echo "::warning::block-explorer not responding on $BE_IP:3001 after 4 minutes"
      ssh "n$BE_NODE_IDX" "docker logs block-explorer 2>&1 | tail -30" || true
    fi
    sleep 10
  done
fi

echo "=== GL0 Cluster ==="
curl -sf "http://$GENESIS_IP:9000/cluster/info" | python3 -c "
import sys,json
peers=json.load(sys.stdin)
for p in peers: print(f\"  {p['state']:12} {p['ip']}:{p['publicPort']}\")
assert len(peers) >= 3, f'Expected 3+ GL0 nodes, got {len(peers)}'
"

echo "=== GL1 Cluster ==="
curl -sf "http://$GENESIS_IP:9010/cluster/info" | python3 -c "
import sys,json
peers=json.load(sys.stdin)
for p in peers: print(f\"  {p['state']:12} {p['ip']}:{p['publicPort']}\")
assert len(peers) >= 3, f'Expected 3+ GL1 nodes, got {len(peers)}'
"

echo "=== Snapshots ==="
curl -sf "http://$GENESIS_IP:9000/global-snapshots/latest" | python3 -c "
import sys,json
ordinal=json.load(sys.stdin)['value']['ordinal']
print(f'Latest ordinal: {ordinal}')
assert ordinal > 0, 'No snapshots produced'
"
