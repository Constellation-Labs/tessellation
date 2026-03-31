#!/usr/bin/env bash
# Realistic network chaos: 3 strong, 4 mild, 1 clean
# Models: 3 nodes on degraded links, 4 with normal cross-region jitter, 1 healthy anchor
set -euo pipefail

CHAOS_DURATION=${CHAOS_DURATION:-300}
RECOVERY_WAIT=${RECOVERY_WAIT:-300}

echo "=== Realistic Network Chaos Test ==="
echo "Profile: 3 strong / 4 mild / 1 clean"
echo "Chaos duration: ${CHAOS_DURATION}s"

# Wait for cluster readiness
echo "[Phase 0] Waiting for 8/8 Ready and ordinal >= 10..."
for attempt in $(seq 1 60); do
  ready=$(docker exec gl0-0 curl -sf localhost:9000/cluster/info 2>/dev/null | jq '[.[] | select(.state=="Ready")] | length' 2>/dev/null || echo 0)
  ord=$(docker exec gl0-0 curl -sf localhost:9000/global-snapshots/latest 2>/dev/null | jq '.value.ordinal' 2>/dev/null || echo 0)
  echo "  attempt $attempt: ready=$ready ordinal=$ord"
  if [[ "$ready" -ge 8 && "$ord" -ge 10 ]]; then
    echo "  ✓ Cluster stable at ordinal $ord"
    break
  fi
  sleep 10
done

PRE_ORD=$(docker exec gl0-0 curl -sf localhost:9000/global-snapshots/latest | jq '.value.ordinal')
echo "[Phase 1] Pre-chaos ordinal: $PRE_ORD"

# Apply chaos profiles
echo "[Phase 2] Applying network degradation..."

# Strong chaos: 3 nodes (gl0-5, gl0-6, gl0-7)
# 4-8s latency with high jitter and packet loss — these nodes will likely get evicted
for node in gl0-5 gl0-6 gl0-7; do
  docker exec $node tc qdisc add dev eth0 root netem delay 4000ms 3000ms loss 10% reorder 25%
  echo "  $node: STRONG (4s ± 3s, 10% loss, 25% reorder)"
done

# Mild chaos: 4 nodes (gl0-1, gl0-2, gl0-3, gl0-4)
# Normal cross-region latency — should stay in consensus
for node in gl0-1 gl0-2 gl0-3 gl0-4; do
  docker exec $node tc qdisc add dev eth0 root netem delay 200ms 100ms loss 1%
  echo "  $node: MILD (200ms ± 100ms, 1% loss)"
done

# Clean: gl0-0 (genesis, API node)
echo "  gl0-0: CLEAN (no degradation)"

echo "[Phase 3] Chaos active for ${CHAOS_DURATION}s..."
sleep "$CHAOS_DURATION"

# Check mid-chaos state
MID_ORD=$(docker exec gl0-0 curl -sf localhost:9000/global-snapshots/latest 2>/dev/null | jq '.value.ordinal' 2>/dev/null || echo "??")
MID_READY=$(docker exec gl0-0 curl -sf localhost:9000/cluster/info 2>/dev/null | jq '[.[] | select(.state=="Ready")] | length' 2>/dev/null || echo "??")
echo "  Mid-chaos: ordinal=$MID_ORD ready=$MID_READY"

# Clear all chaos
echo "[Phase 4] Clearing network degradation..."
for node in gl0-1 gl0-2 gl0-3 gl0-4 gl0-5 gl0-6 gl0-7; do
  docker exec $node tc qdisc del dev eth0 root 2>/dev/null || true
  echo "  $node: cleared"
done

echo "[Phase 5] Recovery window (${RECOVERY_WAIT}s)..."
for attempt in $(seq 1 $((RECOVERY_WAIT / 10))); do
  sleep 10
  ready=$(docker exec gl0-0 curl -sf localhost:9000/cluster/info 2>/dev/null | jq '[.[] | select(.state=="Ready")] | length' 2>/dev/null || echo 0)
  ord=$(docker exec gl0-0 curl -sf localhost:9000/global-snapshots/latest 2>/dev/null | jq '.value.ordinal' 2>/dev/null || echo "??")
  proofs=$(docker exec gl0-0 curl -sf localhost:9000/global-snapshots/latest 2>/dev/null | jq '.proofs | length' 2>/dev/null || echo "??")
  echo "  +${attempt}0s: ordinal=$ord ready=$ready proofs=$proofs"
  if [[ "$ready" -ge 8 && "$proofs" -ge 8 ]]; then
    POST_ORD=$ord
    echo ""
    echo "=== RESULT: PASS ==="
    echo "Pre-chaos: $PRE_ORD | Mid-chaos: $MID_ORD | Post-recovery: $POST_ORD"
    echo "All 8 nodes Ready with 8/8 proofs"
    exit 0
  fi
done

POST_ORD=$(docker exec gl0-0 curl -sf localhost:9000/global-snapshots/latest 2>/dev/null | jq '.value.ordinal' 2>/dev/null || echo "??")
POST_READY=$(docker exec gl0-0 curl -sf localhost:9000/cluster/info 2>/dev/null | jq '[.[] | select(.state=="Ready")] | length' 2>/dev/null || echo "??")
POST_PROOFS=$(docker exec gl0-0 curl -sf localhost:9000/global-snapshots/latest 2>/dev/null | jq '.proofs | length' 2>/dev/null || echo "??")
echo ""
echo "=== RESULT: FAIL ==="
echo "Pre-chaos: $PRE_ORD | Mid-chaos: $MID_ORD | Post-recovery: $POST_ORD"
echo "Ready: $POST_READY/8 | Proofs: $POST_PROOFS/8"
exit 1
