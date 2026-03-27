#!/usr/bin/env bash
# test-network-chaos.sh — Apply random network degradation to a running cluster
# and verify consensus continues producing snapshots.
#
# Usage: ./test-network-chaos.sh [--duration=300] [--check-interval=15]
#
# Applies different latency/jitter/loss profiles to different nodes, then monitors
# that the cluster keeps advancing ordinals. After the chaos window, clears all
# degradation and verifies full recovery.
#
# Requires: NET_ADMIN capability on containers, tc/netem available.

set -euo pipefail

DURATION=${1:-300}       # Total chaos duration in seconds (default 5 min)
CHECK_INTERVAL=${2:-15}  # Polling interval for health checks
GENESIS_PORT=9000

# ── Detect running nodes ──────────────────────────────────────────
NODES=()
for i in $(seq 0 20); do
  if docker inspect "gl0-$i" &>/dev/null; then
    NODES+=("$i")
  fi
done
NUM_NODES=${#NODES[@]}
if [ "$NUM_NODES" -lt 4 ]; then
  echo "ERROR: Need at least 4 gl0 nodes running, found $NUM_NODES"
  exit 1
fi
echo "Found $NUM_NODES GL0 nodes: ${NODES[*]}"

# ── Network degradation profiles ──────────────────────────────────
# Designed to stress consensus without killing the cluster outright.
# Declaration timeout is 35s, so latencies must stay well under that.
#
# Profile 1: Moderate latency + jitter (simulates cross-region)
# Profile 2: High latency + packet reordering (simulates bad link)
# Profile 3: Mild packet loss + latency (simulates lossy wifi)
# Profile 4: Spike latency (intermittent congestion)
# Profile 5: Clean (control node, no degradation)

apply_profile() {
  local node=$1
  local profile=$2
  local container="gl0-$node"

  # Clear any existing qdisc first
  docker exec "$container" tc qdisc del dev eth0 root 2>/dev/null || true

  case $profile in
    1) # Moderate: 200ms ± 100ms
      docker exec "$container" tc qdisc add dev eth0 root netem delay 200ms 100ms distribution normal
      echo "  gl0-$node: moderate latency (200ms ± 100ms)"
      ;;
    2) # High + reorder: 500ms ± 200ms, 25% reorder
      docker exec "$container" tc qdisc add dev eth0 root netem delay 500ms 200ms reorder 25% 50%
      echo "  gl0-$node: high latency + reorder (500ms ± 200ms, 25% reorder)"
      ;;
    3) # Lossy: 100ms ± 50ms, 5% packet loss
      docker exec "$container" tc qdisc add dev eth0 root netem delay 100ms 50ms loss 5%
      echo "  gl0-$node: lossy (100ms ± 50ms, 5% loss)"
      ;;
    4) # Spike: 1000ms ± 500ms (intermittent, applied in bursts)
      docker exec "$container" tc qdisc add dev eth0 root netem delay 1000ms 500ms distribution pareto
      echo "  gl0-$node: spike latency (1000ms ± 500ms pareto)"
      ;;
    5) # Clean control
      echo "  gl0-$node: clean (control)"
      ;;
  esac
}

clear_all() {
  echo ""
  echo "=== Clearing all network degradation ==="
  for i in "${NODES[@]}"; do
    docker exec "gl0-$i" tc qdisc del dev eth0 root 2>/dev/null || true
  done
  echo "All profiles cleared."
}

# Trap to ensure cleanup on exit
trap clear_all EXIT

# ── Phase 1: Verify cluster is healthy ────────────────────────────
echo ""
echo "=== Phase 1: Pre-chaos health check ==="
PRE_ORD=$(curl -sf "http://localhost:$GENESIS_PORT/global-snapshots/latest" | jq '.value.ordinal')
PRE_PROOFS=$(curl -sf "http://localhost:$GENESIS_PORT/global-snapshots/latest" | jq '.proofs | length')
READY=$(curl -sf "http://localhost:$GENESIS_PORT/cluster/info" | jq '[.[] | select(.state=="Ready")] | length')
echo "Ordinal: $PRE_ORD, Proofs: $PRE_PROOFS, Ready: $READY/$NUM_NODES"

if [ "$READY" != "$NUM_NODES" ]; then
  echo "ERROR: Not all nodes Ready. Aborting."
  exit 1
fi
if [ "$PRE_ORD" -lt 5 ]; then
  echo "WARNING: Ordinal < 5, cluster may still be forming. Waiting 60s..."
  sleep 60
  PRE_ORD=$(curl -sf "http://localhost:$GENESIS_PORT/global-snapshots/latest" | jq '.value.ordinal')
fi
echo "Cluster healthy at ordinal $PRE_ORD."

# ── Phase 2: Apply chaos profiles ─────────────────────────────────
echo ""
echo "=== Phase 2: Applying network chaos ==="

# Assign profiles: rotate through 1-5 across nodes
# Keep genesis (node 0) on a mild profile so API remains reachable
PROFILES=(3 1 2 4 5 3 1 2 4 5 3 1 2 4 5 3 1 2 4 5)
apply_profile 0 3  # Genesis: mild loss (API must stay reachable)
for idx in $(seq 1 $((NUM_NODES - 1))); do
  node=${NODES[$idx]}
  profile=${PROFILES[$idx]}
  apply_profile "$node" "$profile"
done

echo ""
echo "=== Phase 3: Monitoring cluster under chaos ($DURATION seconds) ==="
echo ""

START_TIME=$(date +%s)
LAST_ORD=$PRE_ORD
STALL_COUNT=0
MAX_STALL=6  # Allow up to 6 consecutive stalls before failing (~90s at 15s intervals)
ORDINALS_PRODUCED=0
CHECKS=0
ROUND_TIMES=()

while true; do
  NOW=$(date +%s)
  ELAPSED=$((NOW - START_TIME))
  if [ "$ELAPSED" -ge "$DURATION" ]; then
    break
  fi

  sleep "$CHECK_INTERVAL"
  CHECKS=$((CHECKS + 1))

  ORD=$(curl -sf "http://localhost:$GENESIS_PORT/global-snapshots/latest" 2>/dev/null | jq '.value.ordinal' 2>/dev/null || echo "ERR")
  PROOFS=$(curl -sf "http://localhost:$GENESIS_PORT/global-snapshots/latest" 2>/dev/null | jq '.proofs | length' 2>/dev/null || echo "ERR")
  READY=$(curl -sf "http://localhost:$GENESIS_PORT/cluster/info" 2>/dev/null | jq '[.[] | select(.state=="Ready")] | length' 2>/dev/null || echo "ERR")

  if [ "$ORD" = "ERR" ]; then
    echo "  [${ELAPSED}s] API unreachable (genesis node degraded, retrying)"
    STALL_COUNT=$((STALL_COUNT + 1))
  elif [ "$ORD" -gt "$LAST_ORD" ]; then
    ADVANCED=$((ORD - LAST_ORD))
    ORDINALS_PRODUCED=$((ORDINALS_PRODUCED + ADVANCED))
    echo "  [${ELAPSED}s] ordinal=$ORD (+$ADVANCED) proofs=$PROOFS ready=$READY"
    LAST_ORD=$ORD
    STALL_COUNT=0
  else
    STALL_COUNT=$((STALL_COUNT + 1))
    echo "  [${ELAPSED}s] ordinal=$ORD (stalled $STALL_COUNT/$MAX_STALL) proofs=$PROOFS ready=$READY"
  fi

  if [ "$STALL_COUNT" -ge "$MAX_STALL" ]; then
    echo ""
    echo "FAIL: Cluster stalled for $((STALL_COUNT * CHECK_INTERVAL))s under chaos"
    # Still clear chaos via trap, then collect logs
    for i in "${NODES[@]}"; do
      docker logs "gl0-$i" > "/tmp/chaos-gl0-$i.log" 2>&1
    done
    echo "Logs saved to /tmp/chaos-gl0-*.log"
    exit 1
  fi
done

CHAOS_ORD=$LAST_ORD
echo ""
echo "=== Chaos phase complete ==="
echo "Ordinals produced under chaos: $ORDINALS_PRODUCED ($PRE_ORD → $CHAOS_ORD)"

# ── Phase 4: Clear chaos and verify recovery ──────────────────────
# Trap will clear, but we do it explicitly for timing
clear_all
trap - EXIT  # Remove trap since we already cleared

echo ""
echo "=== Phase 4: Post-chaos recovery check (120s) ==="
for attempt in $(seq 1 8); do
  sleep 15
  ORD=$(curl -sf "http://localhost:$GENESIS_PORT/global-snapshots/latest" | jq '.value.ordinal')
  PROOFS=$(curl -sf "http://localhost:$GENESIS_PORT/global-snapshots/latest" | jq '.proofs | length')
  READY=$(curl -sf "http://localhost:$GENESIS_PORT/cluster/info" | jq '[.[] | select(.state=="Ready")] | length')
  echo "  Recovery poll $attempt: ordinal=$ORD proofs=$PROOFS ready=$READY"
  if [ "$READY" = "$NUM_NODES" ] && [ "$ORD" -gt "$CHAOS_ORD" ]; then
    echo ""
    echo "=== PASS: Cluster recovered to full health ==="
    echo "  Pre-chaos ordinal:  $PRE_ORD"
    echo "  Post-chaos ordinal: $CHAOS_ORD (produced $ORDINALS_PRODUCED under degradation)"
    echo "  Recovery ordinal:   $ORD"
    echo "  All $NUM_NODES nodes Ready"

    # Save logs
    for i in "${NODES[@]}"; do
      docker logs "gl0-$i" > "/tmp/chaos-gl0-$i.log" 2>&1
    done
    echo "Logs saved to /tmp/chaos-gl0-*.log"
    exit 0
  fi
done

echo ""
echo "FAIL: Cluster did not fully recover within 120s"
for i in "${NODES[@]}"; do
  docker logs "gl0-$i" > "/tmp/chaos-gl0-$i.log" 2>&1
done
echo "Logs saved to /tmp/chaos-gl0-*.log"
exit 1
