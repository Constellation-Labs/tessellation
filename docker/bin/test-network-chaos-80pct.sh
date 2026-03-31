#!/usr/bin/env bash
# Network chaos test at ~80% of failure boundary
# Aggressive test failed at: 5-15s latency, 10-15% loss
# This test: 4-12s latency, 8-12% loss (80% of aggressive)
#
# Goal: confirm the cluster can survive, stall-detect, evict, and RECOVER
# under sustained degradation that's close to but under the failure boundary.

set -euo pipefail

NUM_GL0="${NUM_GL0_NODES:-8}"
CHAOS_DURATION="${CHAOS_DURATION:-300}"  # 5 min default
WARMUP_ORDINAL="${WARMUP_ORDINAL:-10}"
RECOVERY_TIMEOUT=600  # 10 min for post-chaos recovery

log() { echo "[$(date '+%H:%M:%S')] $*"; }

# ── Cleanup ──────────────────────────────────────────────────────
cleanup() {
  log "Clearing all network degradation..."
  for i in $(seq 0 $((NUM_GL0 - 1))); do
    docker exec "gl0-$i" tc qdisc del dev eth0 root 2>/dev/null || true
  done
  log "Degradation cleared."
}
trap cleanup EXIT

# ── Wait for cluster warmup ──────────────────────────────────────
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

# ── Apply 80% degradation profiles ──────────────────────────────
# Declaration timeout = 35s. Profiles designed so worst-case round-trip < 35s.
#
# Profile spectrum (8 nodes):
#   gl0-0: mild       — 100ms ± 50ms                  (API node, keeps reachable)
#   gl0-1: moderate   — 400ms ± 200ms, 2% loss        (cross-region sim)
#   gl0-2: moderate+  — 600ms ± 300ms, 25% reorder    (bad link)
#   gl0-3: heavy      — 4s ± 2s pareto, 8% loss       (80% of 5s/10%)
#   gl0-4: severe     — 12s ± 8s pareto, 12% loss     (80% of 15s/15%)
#   gl0-5: borderline — 8s ± 6s, 4% loss              (80% of 10s/5%)
#   gl0-6: moderate   — 300ms ± 150ms, 15% loss       (lossy but fast)
#   gl0-7: clean      — control node, no degradation

log "Applying 80% degradation profiles..."

docker exec gl0-0 tc qdisc add dev eth0 root netem delay 100ms 50ms distribution normal
log "  gl0-0: mild (100ms ± 50ms)"

docker exec gl0-1 tc qdisc add dev eth0 root netem delay 400ms 200ms distribution normal loss 2%
log "  gl0-1: moderate (400ms ± 200ms, 2% loss)"

docker exec gl0-2 tc qdisc add dev eth0 root netem delay 600ms 300ms distribution normal reorder 25%
log "  gl0-2: moderate+ (600ms ± 300ms, 25% reorder)"

docker exec gl0-3 tc qdisc add dev eth0 root netem delay 4000ms 2000ms distribution pareto loss 8%
log "  gl0-3: heavy (4s ± 2s pareto, 8% loss)"

docker exec gl0-4 tc qdisc add dev eth0 root netem delay 12000ms 8000ms distribution pareto loss 12%
log "  gl0-4: severe (12s ± 8s pareto, 12% loss)"

docker exec gl0-5 tc qdisc add dev eth0 root netem delay 8000ms 6000ms distribution normal loss 4%
log "  gl0-5: borderline (8s ± 6s, 4% loss)"

docker exec gl0-6 tc qdisc add dev eth0 root netem delay 300ms 150ms distribution normal loss 15%
log "  gl0-6: moderate-lossy (300ms ± 150ms, 15% loss)"

log "  gl0-7: clean (control)"

# ── Monitor during chaos ─────────────────────────────────────────
log "Chaos active for ${CHAOS_DURATION}s. Monitoring..."
CHAOS_START=$(date +%s)
CHAOS_END=$((CHAOS_START + CHAOS_DURATION))
SAMPLES=0
ORDINALS_SEEN=""

while [ "$(date +%s)" -lt "$CHAOS_END" ]; do
  ord=$(curl -sf --max-time 10 http://localhost:9000/global-snapshots/latest 2>/dev/null | jq -r '.value.ordinal // "?"' 2>/dev/null || echo "?")
  proofs=$(curl -sf --max-time 10 http://localhost:9000/global-snapshots/latest 2>/dev/null | jq '.proofs | length // 0' 2>/dev/null || echo "?")
  ready=$(curl -sf --max-time 10 http://localhost:9000/cluster/info 2>/dev/null | jq '[.[] | select(.state == "Ready")] | length' 2>/dev/null || echo "?")
  elapsed=$(( $(date +%s) - CHAOS_START ))
  log "  [${elapsed}s] ordinal=$ord proofs=$proofs ready=$ready/$NUM_GL0"
  ORDINALS_SEEN="$ORDINALS_SEEN $ord"
  SAMPLES=$((SAMPLES + 1))
  sleep 15
done

# ── Clear degradation and monitor recovery ────────────────────────
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

  # Success: all nodes Ready AND at least 2 ordinals advanced since chaos cleared
  if [ "$ready" -ge "$NUM_GL0" ] && [ "$proofs" -ge "$NUM_GL0" ] && [ "$ord" -gt "$((POST_CHAOS_ORD + 1))" ] 2>/dev/null; then
    RECOVERED=true
    log "✅ RECOVERY COMPLETE: ordinal=$ord proofs=$proofs ready=$ready/$NUM_GL0"
    break
  fi
  sleep 15
done

# ── Collect logs ──────────────────────────────────────────────────
for i in $(seq 0 $((NUM_GL0 - 1))); do
  docker logs "gl0-$i" > "/tmp/chaos80-gl0-$i.log" 2>&1
done

# ── Summary ──────────────────────────────────────────────────────
FINAL_ORD=$(curl -sf --max-time 10 http://localhost:9000/global-snapshots/latest 2>/dev/null | jq -r '.value.ordinal // "?"' 2>/dev/null || echo "?")
FINAL_READY=$(curl -sf --max-time 10 http://localhost:9000/cluster/info 2>/dev/null | jq '[.[] | select(.state == "Ready")] | length' 2>/dev/null || echo "?")
FINAL_PROOFS=$(curl -sf --max-time 10 http://localhost:9000/global-snapshots/latest 2>/dev/null | jq '.proofs | length // 0' 2>/dev/null || echo "?")

echo ""
echo "═══════════════════════════════════════════════════"
echo "  80% CHAOS TEST SUMMARY"
echo "═══════════════════════════════════════════════════"
echo "  Pre-chaos ordinal:  $PRE_CHAOS_ORD"
echo "  Post-chaos ordinal: $POST_CHAOS_ORD"
echo "  Final ordinal:      $FINAL_ORD"
echo "  Final ready:        $FINAL_READY/$NUM_GL0"
echo "  Final proofs:       $FINAL_PROOFS"
echo "  Recovered:          $RECOVERED"
echo "  Logs:               /tmp/chaos80-gl0-*.log"
echo "═══════════════════════════════════════════════════"

# ── Log analysis ──────────────────────────────────────────────────
echo ""
echo "Log analysis:"
for i in $(seq 0 $((NUM_GL0 - 1))); do
  WARNS=$(grep -c 'PEER_STALL_WARNING' "/tmp/chaos80-gl0-$i.log" 2>/dev/null || echo 0)
  EVICTS=$(grep -c 'PEER_EVICTION\|VIEW_CHANGE_WITH_EVICTION' "/tmp/chaos80-gl0-$i.log" 2>/dev/null || echo 0)
  RECOVERIES=$(grep -c 'RECOVERY_DOWNLOAD_TRIGGERED\|DOWNLOAD_INIT_RECOVERY' "/tmp/chaos80-gl0-$i.log" 2>/dev/null || echo 0)
  RETRIABLE=$(grep -c 'ROUND_ABANDONED_RETRIABLE' "/tmp/chaos80-gl0-$i.log" 2>/dev/null || echo 0)
  PERSIST_FAIL=$(grep -c 'PERSIST_FAILED' "/tmp/chaos80-gl0-$i.log" 2>/dev/null || echo 0)
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
