#!/usr/bin/env bash
#
# Fork Recovery Matrix Test
# Tests fork recovery with varying cluster sizes and number of isolated nodes.
#
# Two modes:
#   SURVIVAL MODE (default): Verifies cluster continues producing snapshots
#     during isolation and that isolated nodes recover on return.
#   STALL MODE (--expect-stall): Verifies cluster STOPS producing snapshots
#     when too many nodes are isolated (quorum lost), then recovers when
#     nodes return.
#
# Usage:
#   ./docker/bin/test-fork-recovery-matrix.sh <num_gl0_nodes> <num_to_isolate> [--expect-stall]
#
# Examples:
#   ./test-fork-recovery-matrix.sh 7 2           # Expect cluster to survive
#   ./test-fork-recovery-matrix.sh 7 4 --expect-stall  # Expect cluster to stall
#

set -eo pipefail

NUM_GL0=${1:?Usage: $0 <num_gl0_nodes> <num_to_isolate> [--expect-stall]}
NUM_ISOLATE=${2:?Usage: $0 <num_gl0_nodes> <num_to_isolate> [--expect-stall]}
EXPECT_STALL=false
if [ "${3}" = "--expect-stall" ]; then
  EXPECT_STALL=true
fi

GL0_PORT_PREFIX=90
ISOLATION_DURATION=90       # seconds to keep nodes isolated
STALL_CHECK_DURATION=120    # seconds to verify cluster is stalled (--expect-stall)
RECOVERY_TIMEOUT=900        # max seconds to wait for ALL isolated nodes to recover
STABILIZE_WAIT=600          # max seconds to wait for initial cluster stability

# Validate inputs
if [ "$NUM_ISOLATE" -ge "$NUM_GL0" ]; then
  echo "ERROR: Cannot isolate $NUM_ISOLATE of $NUM_GL0 nodes (must leave at least 1)"
  exit 1
fi

REMAINING=$((NUM_GL0 - NUM_ISOLATE))
QUORUM=$((NUM_GL0 / 2 + 1))

# Build list of nodes to isolate (last N nodes)
ISOLATE_NODES=()
for i in $(seq $((NUM_GL0 - NUM_ISOLATE)) $((NUM_GL0 - 1))); do
  ISOLATE_NODES+=("gl0-${i}")
done

# Monitor node: gl0-1 (first non-genesis validator that's never isolated unless almost all are)
MONITOR_NODE="gl0-1"
# For stall tests, also check gl0-0 (genesis) as a remaining node
GENESIS_NODE="gl0-0"

MODE_LABEL="SURVIVAL"
if [ "$EXPECT_STALL" = "true" ]; then
  MODE_LABEL="EXPECTED-STALL"
fi

echo "============================================================"
echo "Fork Recovery Matrix Test: ${NUM_GL0}N isolate ${NUM_ISOLATE} [${MODE_LABEL}]"
echo "============================================================"
echo "  Total nodes:     $NUM_GL0"
echo "  Isolating:       ${ISOLATE_NODES[*]}"
echo "  Remaining:       $REMAINING (quorum: $QUORUM = N/2+1)"
echo "  Expect stall:    $EXPECT_STALL"
echo "  Monitor node:    $MONITOR_NODE"
echo "  Isolation time:  ${ISOLATION_DURATION}s"
echo "  Recovery timeout: ${RECOVERY_TIMEOUT}s"
echo ""

if [ "$EXPECT_STALL" = "true" ] && [ "$REMAINING" -ge "$QUORUM" ]; then
  echo "WARNING: --expect-stall set but remaining=$REMAINING >= quorum=$QUORUM; cluster should NOT stall"
fi
if [ "$EXPECT_STALL" = "false" ] && [ "$REMAINING" -lt "$QUORUM" ]; then
  echo "WARNING: remaining=$REMAINING < quorum=$QUORUM; cluster will likely stall (consider --expect-stall)"
fi

# ── Helpers ────────────────────────────────────────────────────

get_ordinal() {
  local node=$1
  local idx=${node##gl0-}
  local port=$((GL0_PORT_PREFIX * 100 + idx * 10))
  curl -s --connect-timeout 3 --max-time 5 "http://localhost:${port}/global-snapshots/latest" 2>/dev/null | jq -r '.value.ordinal // empty' 2>/dev/null || echo ""
}

get_facilitator_count() {
  local node=$1
  docker logs "$node" 2>&1 | grep "facilitators=" | tail -1 | grep -oP 'facilitators=\d+' | grep -oP '\d+' | head -1 || echo "0"
}

get_node_state() {
  local node=$1
  local idx=${node##gl0-}
  local port=$((GL0_PORT_PREFIX * 100 + idx * 10))
  curl -s --connect-timeout 3 --max-time 5 "http://localhost:${port}/node/info" 2>/dev/null | jq -r '.state // empty' 2>/dev/null || echo ""
}

get_signature_count() {
  local node=$1
  local idx=${node##gl0-}
  local port=$((GL0_PORT_PREFIX * 100 + idx * 10))
  curl -s --connect-timeout 3 --max-time 5 "http://localhost:${port}/global-snapshots/latest" 2>/dev/null | jq '.value.proofs | length' 2>/dev/null || echo "0"
}

get_completed_rounds_after() {
  local node=$1
  local after_ordinal=$2
  docker logs "$node" 2>&1 | grep "Round finished ordinal=" | grep -oP 'ordinal=\d+' | grep -oP '\d+' | awk -v min="$after_ordinal" '$1 > min' | wc -l || echo "0"
}

fail() {
  echo ""
  echo "============================================================"
  echo "FAIL: $1"
  echo "============================================================"
  for node in "${ISOLATE_NODES[@]}"; do
    docker exec --privileged "$node" tc qdisc del dev eth0 root 2>/dev/null || true
  done
  exit 1
}

pass() {
  echo ""
  echo "============================================================"
  echo "PASS: Fork Recovery [${MODE_LABEL}] (${NUM_GL0}N, isolate ${NUM_ISOLATE})"
  echo "============================================================"
  echo "$1"
  exit 0
}

# ── Phase 1: Wait for cluster stability ────────────────────────

echo "Phase 1: Waiting for ${NUM_GL0}-node cluster to stabilize (${STABILIZE_WAIT}s)..."

deadline=$(($(date +%s) + STABILIZE_WAIT))
stable=false
while [ "$(date +%s)" -lt "$deadline" ]; do
  all_synced=true
  min_ord=999999
  max_ord=0
  status_line=""

  for i in $(seq 0 $((NUM_GL0 - 1))); do
    node="gl0-${i}"
    ord=$(get_ordinal "$node")
    fac=$(get_facilitator_count "$node")
    status_line="${status_line} ${node}:ord=${ord:-?}/fac=${fac:-?}"

    if [ "$i" -eq 0 ]; then continue; fi

    if [ -z "$ord" ] || [ "$ord" -lt 5 ] || [ "${fac:-0}" -lt "$NUM_GL0" ]; then
      all_synced=false
    fi

    if [ -n "$ord" ]; then
      [ "$ord" -lt "$min_ord" ] && min_ord=$ord
      [ "$ord" -gt "$max_ord" ] && max_ord=$ord
    fi
  done

  spread=$((max_ord - min_ord))
  if [ "$all_synced" = true ] && [ "$spread" -le 1 ]; then
    echo "  Cluster synchronized: ${status_line}"
    stable=true
    break
  fi

  echo "  Waiting...${status_line} spread=${spread}"
  sleep 10
done

if [ "$stable" != "true" ]; then
  fail "Cluster did not stabilize within ${STABILIZE_WAIT}s"
fi

# Wait for 3 stable rounds with full facilitator set
echo "  Waiting for 3 stable rounds with ${NUM_GL0} facilitators..."
stable_rounds=0
stab_deadline=$(($(date +%s) + 300))
while [ "$(date +%s)" -lt "$stab_deadline" ] && [ "$stable_rounds" -lt 3 ]; do
  fac=$(get_facilitator_count "$MONITOR_NODE")
  ord=$(get_ordinal "$MONITOR_NODE")
  if [ "${fac:-0}" -eq "$NUM_GL0" ]; then
    stable_rounds=$((stable_rounds + 1))
    echo "    Round $stable_rounds/3 with fac=$fac at ordinal $ord"
  else
    stable_rounds=0
    echo "    Waiting... fac=${fac:-?} at ordinal ${ord:-?} (need $NUM_GL0)"
  fi
  sleep 45
done
if [ "$stable_rounds" -lt 3 ]; then
  echo "  WARNING: Only $stable_rounds/3 stable rounds, proceeding anyway"
fi

# Record pre-isolation state
pre_ordinal=$(get_ordinal "$MONITOR_NODE")
echo ""
echo "  Pre-isolation state:"
for i in $(seq 0 $((NUM_GL0 - 1))); do
  node="gl0-${i}"
  ord=$(get_ordinal "$node")
  fac=$(get_facilitator_count "$node")
  sigs=$(get_signature_count "$node")
  echo "    $node: ordinal=$ord facilitators=$fac signatures=$sigs"
done

# ── Phase 2: Isolate nodes ────────────────────────────────────

echo ""
echo "Phase 2: Isolating ${NUM_ISOLATE} nodes: ${ISOLATE_NODES[*]}..."

for node in "${ISOLATE_NODES[@]}"; do
  docker exec --privileged "$node" tc qdisc add dev eth0 root netem loss 100% 2>&1 || \
    fail "Could not isolate $node (needs --privileged or NET_ADMIN)"
  echo "  Isolated $node"
done

if [ "$EXPECT_STALL" = "true" ]; then
  # ── STALL MODE: Verify cluster STOPS ──────────────────────────
  echo ""
  echo "  [STALL MODE] Waiting ${ISOLATION_DURATION}s then checking cluster stalled..."
  sleep "$ISOLATION_DURATION"

  # Check ordinal didn't advance (or advanced very little — allow +1 for in-flight round)
  post_isolation_ordinal=$(get_ordinal "$GENESIS_NODE")
  if [ -z "$post_isolation_ordinal" ]; then
    # Node might be returning 503 if it's in recovery — that's also stalled
    echo "  gl0-0 returning empty/503 — cluster appears stalled (good)"
    post_isolation_ordinal="$pre_ordinal"
  fi
  advancement=$((post_isolation_ordinal - pre_ordinal))

  echo "  Cluster state after isolation:"
  echo "    Ordinal: $pre_ordinal → ${post_isolation_ordinal} (advancement: $advancement)"

  if [ "$advancement" -gt 2 ]; then
    fail "Cluster should have stalled (remaining=$REMAINING < quorum=$QUORUM) but advanced $advancement ordinals!"
  fi
  echo "  ✓ Cluster correctly stalled (advancement=$advancement ≤ 2)"

  # Now verify it stays stalled for another check period
  echo "  Verifying stall persists for ${STALL_CHECK_DURATION}s..."
  stall_start_ordinal="$post_isolation_ordinal"
  sleep "$STALL_CHECK_DURATION"
  stall_check_ordinal=$(get_ordinal "$GENESIS_NODE")
  stall_check_ordinal=${stall_check_ordinal:-$stall_start_ordinal}
  stall_advancement=$((stall_check_ordinal - stall_start_ordinal))

  if [ "$stall_advancement" -gt 0 ]; then
    fail "Cluster advanced $stall_advancement ordinals during stall check — quorum should have prevented this"
  fi
  echo "  ✓ Stall confirmed (ordinal unchanged at $stall_check_ordinal for ${STALL_CHECK_DURATION}s)"

  # Record for recovery phase
  post_isolation_ordinal="$stall_check_ordinal"

else
  # ── SURVIVAL MODE: Verify cluster continues ───────────────────
  echo "  [SURVIVAL MODE] Waiting ${ISOLATION_DURATION}s for cluster to advance with $REMAINING nodes..."
  sleep "$ISOLATION_DURATION"

  post_isolation_ordinal=$(get_ordinal "$MONITOR_NODE")
  post_fac=$(get_facilitator_count "$MONITOR_NODE")
  post_sigs=$(get_signature_count "$MONITOR_NODE")
  echo "  Cluster state after isolation:"
  echo "    Ordinal: $pre_ordinal → ${post_isolation_ordinal:-?}"
  echo "    Facilitators: ${post_fac:-?}"
  echo "    Signatures: ${post_sigs:-?}"

  if [ -z "$post_isolation_ordinal" ] || [ "$post_isolation_ordinal" -le "$pre_ordinal" ]; then
    fail "Cluster did not advance during isolation (stuck at ordinal $pre_ordinal)"
  fi

  advancement=$((post_isolation_ordinal - pre_ordinal))
  echo "  ✓ Cluster produced $advancement snapshots with $REMAINING/$NUM_GL0 nodes"
fi

# ── Phase 3: Restore network and monitor recovery ──────────────

echo ""
echo "Phase 3: Restoring network for ${ISOLATE_NODES[*]}..."

for node in "${ISOLATE_NODES[@]}"; do
  docker exec --privileged "$node" tc qdisc del dev eth0 root 2>&1 || \
    echo "  Warning: tc qdisc del failed for $node"
  echo "  Restored $node"
done

echo "  Monitoring recovery (timeout: ${RECOVERY_TIMEOUT}s)..."

recovery_start=$(date +%s)
recovery_deadline=$((recovery_start + RECOVERY_TIMEOUT))
all_recovered=false

# For stall mode, we also need the REMAINING nodes to resume
# Track which nodes have recovered
declare -A node_recovered
for node in "${ISOLATE_NODES[@]}"; do
  node_recovered[$node]=false
done

# For stall mode, additionally track if the cluster resumed producing
cluster_resumed=false
if [ "$EXPECT_STALL" = "false" ]; then
  cluster_resumed=true  # Already producing in survival mode
fi

while [ "$(date +%s)" -lt "$recovery_deadline" ]; do
  elapsed=$(( $(date +%s) - recovery_start ))
  all_done=true
  status_line=""

  # Check if cluster resumed (stall mode)
  if [ "$cluster_resumed" = "false" ]; then
    current_ord=$(get_ordinal "$GENESIS_NODE")
    current_ord=${current_ord:-$post_isolation_ordinal}
    if [ "$current_ord" -gt "$((post_isolation_ordinal + 2))" ]; then
      cluster_resumed=true
      echo "  [${elapsed}s] CLUSTER RESUMED at ordinal $current_ord (was stalled at $post_isolation_ordinal)"
    else
      status_line=" cluster:stalled@${current_ord}"
      all_done=false
    fi
  fi

  for node in "${ISOLATE_NODES[@]}"; do
    if [ "${node_recovered[$node]}" = "true" ]; then
      status_line="${status_line} ${node}:RECOVERED"
      continue
    fi

    completed=$(get_completed_rounds_after "$node" "$post_isolation_ordinal")
    fac=$(get_facilitator_count "$node")
    state=$(get_node_state "$node")
    status_line="${status_line} ${node}:completed=${completed:-0}/fac=${fac:-?}/state=${state:-?}"

    if [ -n "$completed" ] && [ "$completed" -ge 2 ]; then
      node_recovered[$node]=true
      echo "  [${elapsed}s] $node RECOVERED (completed $completed rounds after ordinal $post_isolation_ordinal)"
    else
      all_done=false
    fi
  done

  if [ "$all_done" = true ]; then
    all_recovered=true
    echo "  [${elapsed}s] ALL nodes recovered!"
    break
  fi

  echo "  [${elapsed}s]${status_line}"
  sleep 15
done

# ── Phase 4: Verify results ────────────────────────────────────

echo ""
echo "Phase 4: Verifying results..."

final_ordinal=$(get_ordinal "$MONITOR_NODE")
final_fac=$(get_facilitator_count "$MONITOR_NODE")
final_sigs=$(get_signature_count "$MONITOR_NODE")
recovery_elapsed=$(( $(date +%s) - recovery_start ))

echo ""
echo "  Final cluster state:"
for i in $(seq 0 $((NUM_GL0 - 1))); do
  node="gl0-${i}"
  ord=$(get_ordinal "$node")
  fac=$(get_facilitator_count "$node")
  sigs=$(get_signature_count "$node")
  state=$(get_node_state "$node")
  echo "    $node: ordinal=${ord:-?} facilitators=${fac:-?} signatures=${sigs:-?} state=${state:-?}"
done

echo ""
echo "  Summary:"
echo "    Mode:              $MODE_LABEL"
echo "    Initial ordinal:   $pre_ordinal"
echo "    Post-isolation:    $post_isolation_ordinal"
echo "    Final ordinal:     ${final_ordinal:-?}"
echo "    Final facilitators: ${final_fac:-?} (expected: $NUM_GL0)"
echo "    Final signatures:  ${final_sigs:-?} (expected: $NUM_GL0)"
echo "    Recovery time:     ${recovery_elapsed}s"

if [ "$all_recovered" != "true" ]; then
  echo ""
  echo "  Nodes that did NOT recover:"
  for node in "${ISOLATE_NODES[@]}"; do
    if [ "${node_recovered[$node]}" != "true" ]; then
      echo "    $node"
      echo "    Last 5 log lines:"
      docker logs "$node" 2>&1 | grep -E "consecutiveAbandonments|ROUND_ABANDONED|QUORUM_INFEASIBLE|DOWNLOAD_INIT" | tail -5 | sed 's/^/      /'
    fi
  done
  fail "Not all isolated nodes recovered within ${RECOVERY_TIMEOUT}s"
fi

if [ "$EXPECT_STALL" = "true" ] && [ "$cluster_resumed" != "true" ]; then
  fail "Cluster did not resume after restoring isolated nodes"
fi

if [ "${final_fac:-0}" -lt "$NUM_GL0" ]; then
  echo "  WARNING: Final facilitator count ${final_fac} < expected ${NUM_GL0}"
fi

pass "${NUM_ISOLATE} nodes isolated, ${MODE_LABEL} verified, all recovered in ${recovery_elapsed}s (ordinal $pre_ordinal → ${final_ordinal:-?})"
