#!/usr/bin/env bash
# Network chaos test — mid-range profiles
# Mild test passed (100ms-1s), 80% test failed (4s-12s)
# Mid-range: worst node at 2s, most under 1s
#
# Goal: find the stability boundary between mild and severe.

set -euo pipefail

NUM_GL0="${NUM_GL0_NODES:-8}"
CHAOS_DURATION="${CHAOS_DURATION:-300}"  # 5 min default
WARMUP_ORDINAL="${WARMUP_ORDINAL:-10}"
RECOVERY_TIMEOUT=600  # 10 min

log() { echo "[$(date '+%H:%M:%S')] $*"; }

cleanup() {
  log "Clearing all network degradation..."
  for i in $(seq 0 $((NUM_GL0 - 1))); do
    docker exec "gl0-$i" tc qdisc del dev eth0 root 2>/dev/null || true
  done
  log "Degradation cleared."
}
trap cleanup EXIT

log "Waiting for cluster warmup (ordinal >= $WARMUP_ORDINAL, all $NUM_GL0 Ready)..."
for attempt in $(seq 1 120); do
  ord=$(curl -sf http://localhost:9000/global-snapshots/latest 2>/dev/null | jq -r '.value.ordinal // 0' 2>/dev/null || echo 0)
  ready=$(curl -sf http://localhost:9000/cluster/info 2>/dev/null | jq '[.[] | select(.state == "Ready")] | length' 2>/dev/null || echo 0)
  proofs=$(curl -sf http://localhost:9000/global-snapshots/latest 2>/dev/null | jq '.proofs | length' 2>/dev/null || echo 0)
  if [ "$ord" -ge "$WARMUP_ORDINAL" ] && [ "$ready" -ge "$NUM_GL0" ] && [ "$proofs" -ge "$NUM_GL0" ]; then
    log "Cluster warm: ordinal=$ord ready=$ready/$NUM_GL0 proofs=$proofs"
    break
  fi
  [ $((attempt % 10)) -eq 0 ] && log "Warmup: ordinal=$ord ready=$ready/$NUM_GL0 proofs=$proofs (attempt $attempt)"
  sleep 5
done

PRE_CHAOS_ORD=$(curl -sf http://localhost:9000/global-snapshots/latest | jq -r '.value.ordinal')
log "Pre-chaos ordinal: $PRE_CHAOS_ORD"

# Profile spectrum — binary search midpoint between mild (100ms-1s) and severe (4s-12s):
#   gl0-0: 100ms ± 50ms                  (API, same as mild)
#   gl0-1: 200ms ± 100ms, 1% loss        (slightly worse than mild)
#   gl0-2: 400ms ± 200ms, 2% loss        (cross-region)
#   gl0-3: 800ms ± 400ms, 3% loss        (bad cross-region)
#   gl0-4: 2000ms ± 1000ms, 5% loss      (worst node — well under 35s timeout)
#   gl0-5: 1500ms ± 700ms, 3% loss       (degraded)
#   gl0-6: 300ms ± 150ms, 8% loss        (fast but lossy)
#   gl0-7: clean                          (control)

log "Applying mid-range degradation profiles..."

docker exec gl0-0 tc qdisc add dev eth0 root netem delay 100ms 50ms distribution normal
log "  gl0-0: mild (100ms ± 50ms)"

docker exec gl0-1 tc qdisc add dev eth0 root netem delay 200ms 100ms distribution normal loss 1%
log "  gl0-1: light (200ms ± 100ms, 1% loss)"

docker exec gl0-2 tc qdisc add dev eth0 root netem delay 400ms 200ms distribution normal loss 2%
log "  gl0-2: moderate (400ms ± 200ms, 2% loss)"

docker exec gl0-3 tc qdisc add dev eth0 root netem delay 800ms 400ms distribution normal loss 3%
log "  gl0-3: bad-region (800ms ± 400ms, 3% loss)"

docker exec gl0-4 tc qdisc add dev eth0 root netem delay 2000ms 1000ms distribution pareto loss 5%
log "  gl0-4: worst (2s ± 1s pareto, 5% loss)"

docker exec gl0-5 tc qdisc add dev eth0 root netem delay 1500ms 700ms distribution normal loss 3%
log "  gl0-5: degraded (1.5s ± 700ms, 3% loss)"

docker exec gl0-6 tc qdisc add dev eth0 root netem delay 300ms 150ms distribution normal loss 8%
log "  gl0-6: lossy (300ms ± 150ms, 8% loss)"

log "  gl0-7: clean (control)"

log "Chaos active for ${CHAOS_DURATION}s. Monitoring..."
CHAOS_START=$(date +%s)
CHAOS_END=$((CHAOS_START + CHAOS_DURATION))

while [ "$(date +%s)" -lt "$CHAOS_END" ]; do
  ord=$(curl -sf --max-time 10 http://localhost:9000/global-snapshots/latest 2>/dev/null | jq -r '.value.ordinal // "?"' 2>/dev/null || echo "?")
  proofs=$(curl -sf --max-time 10 http://localhost:9000/global-snapshots/latest 2>/dev/null | jq '.proofs | length // 0' 2>/dev/null || echo "?")
  ready=$(curl -sf --max-time 10 http://localhost:9000/cluster/info 2>/dev/null | jq '[.[] | select(.state == "Ready")] | length' 2>/dev/null || echo "?")
  elapsed=$(( $(date +%s) - CHAOS_START ))
  log "  [${elapsed}s] ordinal=$ord proofs=$proofs ready=$ready/$NUM_GL0"
  sleep 15
done

cleanup
trap - EXIT

POST_CHAOS_ORD=$(curl -sf --max-time 10 http://localhost:9000/global-snapshots/latest 2>/dev/null | jq -r '.value.ordinal // "?"' 2>/dev/null || echo "?")
log "Post-chaos ordinal: $POST_CHAOS_ORD (pre-chaos was $PRE_CHAOS_ORD)"

log "Monitoring recovery (up to ${RECOVERY_TIMEOUT}s)..."
RECOVERY_START=$(date +%s)
RECOVERED=false

while [ $(( $(date +%s) - RECOVERY_START )) -lt "$RECOVERY_TIMEOUT" ]; do
  ord=$(curl -sf --max-time 10 http://localhost:9000/global-snapshots/latest 2>/dev/null | jq -r '.value.ordinal // 0' 2>/dev/null || echo 0)
  proofs=$(curl -sf --max-time 10 http://localhost:9000/global-snapshots/latest 2>/dev/null | jq '.proofs | length // 0' 2>/dev/null || echo 0)
  ready=$(curl -sf --max-time 10 http://localhost:9000/cluster/info 2>/dev/null | jq '[.[] | select(.state == "Ready")] | length' 2>/dev/null || echo 0)
  elapsed=$(( $(date +%s) - RECOVERY_START ))
  log "  [recovery +${elapsed}s] ordinal=$ord proofs=$proofs ready=$ready/$NUM_GL0"

  if [ "$ready" -ge "$NUM_GL0" ] && [ "$proofs" -ge "$NUM_GL0" ] && [ "$ord" -gt "$((POST_CHAOS_ORD + 1))" ] 2>/dev/null; then
    RECOVERED=true
    log "✅ RECOVERY COMPLETE: ordinal=$ord proofs=$proofs ready=$ready/$NUM_GL0"
    break
  fi
  sleep 15
done

for i in $(seq 0 $((NUM_GL0 - 1))); do
  docker logs "gl0-$i" > "/tmp/chaos-mid-gl0-$i.log" 2>&1
done

FINAL_ORD=$(curl -sf --max-time 10 http://localhost:9000/global-snapshots/latest 2>/dev/null | jq -r '.value.ordinal // "?"' 2>/dev/null || echo "?")
FINAL_READY=$(curl -sf --max-time 10 http://localhost:9000/cluster/info 2>/dev/null | jq '[.[] | select(.state == "Ready")] | length' 2>/dev/null || echo "?")
FINAL_PROOFS=$(curl -sf --max-time 10 http://localhost:9000/global-snapshots/latest 2>/dev/null | jq '.proofs | length // 0' 2>/dev/null || echo "?")
ORDINALS_PRODUCED=$((FINAL_ORD - PRE_CHAOS_ORD))

echo ""
echo "═══════════════════════════════════════════════════"
echo "  MID-RANGE CHAOS TEST SUMMARY"
echo "═══════════════════════════════════════════════════"
echo "  Pre-chaos ordinal:  $PRE_CHAOS_ORD"
echo "  Post-chaos ordinal: $POST_CHAOS_ORD"
echo "  Final ordinal:      $FINAL_ORD"
echo "  Ordinals produced:  $ORDINALS_PRODUCED"
echo "  Final ready:        $FINAL_READY/$NUM_GL0"
echo "  Final proofs:       $FINAL_PROOFS"
echo "  Recovered:          $RECOVERED"
echo "  Logs:               /tmp/chaos-mid-gl0-*.log"
echo "═══════════════════════════════════════════════════"

echo ""
echo "Log analysis:"
for i in $(seq 0 $((NUM_GL0 - 1))); do
  WARNS=$(grep -c 'PEER_STALL_WARNING' "/tmp/chaos-mid-gl0-$i.log" 2>/dev/null || echo 0)
  EVICTS=$(grep -c 'PEER_EVICTION\|VIEW_CHANGE_WITH_EVICTION' "/tmp/chaos-mid-gl0-$i.log" 2>/dev/null || echo 0)
  RECOVERIES=$(grep -c 'RECOVERY_DOWNLOAD_TRIGGERED\|DOWNLOAD_INIT_RECOVERY' "/tmp/chaos-mid-gl0-$i.log" 2>/dev/null || echo 0)
  RETRIABLE=$(grep -c 'ROUND_ABANDONED_RETRIABLE' "/tmp/chaos-mid-gl0-$i.log" 2>/dev/null || echo 0)
  PERSIST_FAIL=$(grep -c 'PERSIST_FAILED' "/tmp/chaos-mid-gl0-$i.log" 2>/dev/null || echo 0)
  echo "  gl0-$i: warnings=$WARNS evictions=$EVICTS recoveries=$RECOVERIES retriable=$RETRIABLE persist_fail=$PERSIST_FAIL"
done

if [ "$RECOVERED" = "true" ]; then
  echo ""
  echo "✅ TEST PASSED"
  exit 0
else
  echo ""
  echo "❌ TEST FAILED — cluster did not fully recover"
  exit 1
fi
