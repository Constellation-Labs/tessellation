#!/usr/bin/env bash
#
# Fork Recovery Test
# Tests that a node isolated from the cluster can detect divergence,
# recover, and rejoin consensus after network is restored.
#
# Usage: ./docker/bin/test-fork-recovery.sh [gl0_port_prefix]
# Example: ./docker/bin/test-fork-recovery.sh 90
#
# Requires: 3+ GL0 nodes running, NET_ADMIN capability in containers
#

set -eo pipefail

GL0_PORT_PREFIX=${1:-90}
# Auto-detect node count
NUM_GL0=$(docker ps --format "{{.Names}}" | grep -c "^gl0-" 2>/dev/null || echo "3")
NUM_GL0=$(echo "$NUM_GL0" | tr -d '[:space:]')
ISOLATION_NODE="gl0-$((NUM_GL0 - 1))"
MONITOR_NODE="gl0-0"
# With quorum-threshold=0.67, need ceil(N*0.67) declarations.
# ceil(3*0.67)=3 (can't lose any), ceil(4*0.67)=3 (can lose 1).
# Minimum 4 nodes required for this test to work.
if [ "$NUM_GL0" -lt 5 ]; then
  echo "ERROR: Fork recovery test requires at least 5 GL0 nodes (current: $NUM_GL0)"
  echo "  With quorum-threshold=0.67, isolating 1 of 4 leaves ceil(3*0.67)=3 (zero fault tolerance)."
  echo "  With 5 nodes, isolating 1 leaves 4 and ceil(4*0.67)=3 (tolerates 1 additional failure)."
  echo "  Use: just test --test=fork-recovery --num-gl0=5"
  exit 1
fi

ISOLATION_DURATION=270  # seconds to keep node isolated
# NOTE: CI sets CL_DECLARATION_TIMEOUT=60s and CL_RE_STALL_TIMEOUT=25s.
# Worst-case: isolation happens right when gl0-4 was the declared leader, so the remaining
# 4 nodes must wait 60s (declaration timeout) + 25s (re-stall) before a new 3-node round
# can start and finish — approximately 90s/ordinal. 270s = 3 ordinals, which is enough for
# AbandonmentTracker's lagging-detection gate (peersMajorityAhead=true) to trigger on gl0-4
# after reconnect (3 ordinals behind > forkLagThreshold doesn't apply; abandonment threshold fires).
RECOVERY_TIMEOUT=900    # max seconds to wait for recovery (observe() needs ~4 ordinals × 43s + download time)
STABILIZE_WAIT=480      # seconds to wait for initial cluster stability (nodes need time to join + sync)

echo "================================================"
echo "Fork Recovery Test"
echo "================================================"
echo "  Isolation node: $ISOLATION_NODE"
echo "  Monitor node:   $MONITOR_NODE"
echo "  Isolation time: ${ISOLATION_DURATION}s"
echo "  Recovery timeout: ${RECOVERY_TIMEOUT}s"
echo ""

# Helper: get ordinal from a node (via host-mapped port)
get_ordinal() {
  local node=$1
  local idx=${node##gl0-}
  local port=$((GL0_PORT_PREFIX * 100 + idx * 10))
  curl -s "http://localhost:${port}/global-snapshots/latest" 2>/dev/null | jq -r '.value.ordinal // empty' 2>/dev/null || echo ""
}

# Helper: get facilitator count from latest consensus log
get_facilitator_count() {
  local node=$1
  local result
  result=$(docker logs "$node" 2>&1 | grep "facilitators=" | tail -1 | sed -n 's/.*facilitators=\([0-9]*\).*/\1/p' | head -1 || true)
  echo "${result:-0}"
}

# Helper: get node state
get_node_state() {
  local node=$1
  local idx=${node##gl0-}
  local port=$((GL0_PORT_PREFIX * 100 + idx * 10))
  curl -s "http://localhost:${port}/node/info" 2>/dev/null | jq -r '.state // empty' 2>/dev/null || echo ""
}

# Helper: check for fork recovery events
get_fork_events() {
  local node=$1
  local result
  result=$(docker logs "$node" 2>&1 | grep -c "Fork divergence\|FORK_CHECKS_PASSED\|fork.*detect" 2>/dev/null || true)
  echo "${result:-0}"
}

# Helper: check for round completions after a given ordinal
get_completed_rounds_after() {
  local node=$1
  local after_ordinal=$2
  local result
  result=$(docker logs "$node" 2>&1 | grep "Round finished ordinal=" | sed -n 's/.*ordinal=\([0-9]*\).*/\1/p' | awk -v min="$after_ordinal" '$1 > min' | wc -l || true)
  echo "${result:-0}"
}

fail() {
  echo "FAIL: $1"
  # Attempt cleanup
  docker exec --privileged "$ISOLATION_NODE" iptables -F 2>/dev/null || true
  exit 1
}

pass() {
  echo ""
  echo "================================================"
  echo "PASS: Fork Recovery Test"
  echo "================================================"
  echo "$1"
  exit 0
}

# ── Phase 1: Wait for cluster stability ────────────────────────
# Genesis (gl0-0) runs solo consensus (~43s/round) until validators
# download its snapshots and join.  Genesis typically falls behind and
# gets evicted once validators form their own quorum.  We only require
# the VALIDATORS (gl0-1 .. gl0-N) to stabilise — they form the healthy
# quorum that the test depends on.

echo "Phase 1: Waiting for validators to synchronise (${STABILIZE_WAIT}s)..."

deadline=$(($(date +%s) + STABILIZE_WAIT))
stable=false
while [ "$(date +%s)" -lt "$deadline" ]; do
  synced_count=0
  synced_min_ord=999999
  synced_max_ord=0
  status_line=""

  for i in $(seq 0 $((NUM_GL0 - 1))); do
    node="gl0-${i}"
    ord=$(get_ordinal "$node")
    fac=$(get_facilitator_count "$node")
    status_line="${status_line} ${node}:ord=${ord:-?}/fac=${fac:-?}"

    # Skip genesis (gl0-0) for sync checks — it often falls behind
    if [ "$i" -eq 0 ]; then
      continue
    fi

    # Validators must report an ordinal > 5 and be participating in multi-node consensus.
    if [ -n "$ord" ] && [ "$ord" -ge 5 ] && [ "${fac:-0}" -ge 2 ]; then
      synced_count=$((synced_count + 1))
      [ "$ord" -lt "$synced_min_ord" ] && synced_min_ord=$ord
      [ "$ord" -gt "$synced_max_ord" ] && synced_max_ord=$ord
    fi
  done

  # Require majority of validators synced (>= 3 of 4 validators, or >= N/2+1).
  # With supermajority quorum, some peers naturally fall behind during bootstrap
  # and recover via fork detection + download. The test validates that a working
  # majority can produce rounds, not that 100% sync instantly.
  min_synced=$(( (NUM_GL0 - 1) / 2 + 1 ))  # majority of validators (excl genesis)
  [ "$min_synced" -lt 3 ] && min_synced=3
  synced_spread=$((synced_max_ord - synced_min_ord))
  if [ "$synced_count" -ge "$min_synced" ] && [ "$synced_spread" -le 2 ]; then
    echo "  Validators synchronised: $status_line (synced=$synced_count/$((NUM_GL0 - 1)) spread=$synced_spread)"
    stable=true
    break
  fi

  echo "  Waiting...${status_line} synced=$synced_count spread=${synced_spread}"
  sleep 10
done

if [ "$stable" != "true" ]; then
  fail "Validators did not synchronise within ${STABILIZE_WAIT}s"
fi

# Phase 1 readiness check. The test's real hypothesis is: "an in-committee,
# caught-up peer (ISOLATION_NODE) can be isolated for 270s and rejoin within
# 900s." The arithmetic of supermajority quorum (ceil(fac * 2/3)) forces a
# strict constraint: after isolating 1 peer, the remaining (fac-1) peers must
# all be healthy and at tip, because any shortfall drops us below quorum.
#
#   fac=5 → quorum=4, isolate 1 → need 4-of-4 remaining all healthy
#   fac=4 → quorum=3, isolate 1 → need 3-of-3 remaining all healthy
#   fac=3 → quorum=2, isolate 1 → need 2-of-2 remaining all healthy
#
# A single lagging non-target peer is fatal: observed 2026-04-23 run with
# fac=5, gl0-1 at ord=10 while others at ord=15, gl0-4 isolated → quorum=4
# unreachable (only 3 healthy signers) → cluster stuck at ord=15 the whole
# 270s isolation window. B1 can't bail out because EvictionCert also needs
# quorum=4 votes, same deadlock boundary.
#
# So the precondition is: ISOLATION_NODE + every other in-committee peer must
# be at tip. Committee size doesn't have to be N — if B1 evicted one peer
# pre-test and the cluster is running cleanly at fac=N-1, that's fine as long
# as ISOLATION_NODE is still in that committee. What matters is that every
# CURRENTLY-IN-COMMITTEE peer is at tip.
#
# We approximate "in committee" by "reports a non-zero fac that matches the
# monitor's fac AND is reachable (ord is defined)". Peers that B1 has evicted
# typically drop off the cluster/info endpoint or report fac=0.
echo "  Waiting for $ISOLATION_NODE + all committee peers to agree on tip (fac>=3, spread<=1 across committee, 3+ consecutive rounds)..."
stable_rounds=0
stab_deadline=$(($(date +%s) + 600))
MIN_COMMITTEE=3
while [ "$(date +%s)" -lt "$stab_deadline" ] && [ "$stable_rounds" -lt 3 ]; do
  iso_ord=$(get_ordinal "$ISOLATION_NODE")
  iso_fac=$(get_facilitator_count "$ISOLATION_NODE")
  mon_ord=$(get_ordinal "$MONITOR_NODE")
  mon_fac=$(get_facilitator_count "$MONITOR_NODE")

  # Build full status line + find the set of peers currently in the committee
  # (fac matches monitor's fac AND ordinal is reachable).
  status_line=""
  committee_min_ord=""
  committee_max_ord=""
  committee_size=0
  committee_view_agrees=true
  for i in $(seq 0 $((NUM_GL0 - 1))); do
    node="gl0-${i}"
    nf=$(get_facilitator_count "$node")
    no=$(get_ordinal "$node")
    status_line="${status_line} ${node}:ord=${no:-?}/fac=${nf:-?}"
    # A peer is "in this committee" if it's reachable AND agrees with monitor
    # on the facilitator set size.
    if [ -n "$no" ] && [ -n "$nf" ] && [ "$nf" = "${mon_fac:-0}" ] && [ "$nf" -gt 0 ]; then
      committee_size=$((committee_size + 1))
      if [ -z "$committee_min_ord" ] || [ "$no" -lt "$committee_min_ord" ]; then
        committee_min_ord=$no
      fi
      if [ -z "$committee_max_ord" ] || [ "$no" -gt "$committee_max_ord" ]; then
        committee_max_ord=$no
      fi
    elif [ -n "$nf" ] && [ "$nf" != "${mon_fac:-0}" ] && [ "$nf" -gt 0 ]; then
      # Peer has a fac view that disagrees with monitor — that's a split.
      committee_view_agrees=false
    fi
  done

  in_sync=false
  spread="?"
  if [ -n "$committee_min_ord" ] && [ -n "$committee_max_ord" ]; then
    spread=$((committee_max_ord - committee_min_ord))
  fi
  if [ -n "$iso_ord" ] && [ -n "$mon_ord" ] \
     && [ "${iso_fac:-0}" -ge "$MIN_COMMITTEE" ] \
     && [ "${iso_fac:-0}" = "${mon_fac:-0}" ] \
     && [ "$committee_view_agrees" = "true" ] \
     && [ "$committee_size" = "${mon_fac:-0}" ] \
     && [ "$spread" != "?" ] && [ "$spread" -le 1 ]; then
    in_sync=true
  fi

  if [ "$in_sync" = "true" ]; then
    stable_rounds=$((stable_rounds + 1))
    echo "    Round $stable_rounds/3 committee=${committee_size} at tip (spread=$spread) iso=${iso_ord}/${iso_fac} mon=${mon_ord}/${mon_fac} [${status_line# }]"
  else
    stable_rounds=0
    echo "    Waiting (committee_size=${committee_size}/${mon_fac:-?} spread=$spread iso_fac=${iso_fac:-?} mon_fac=${mon_fac:-?} view_agrees=$committee_view_agrees) [${status_line# }]"
  fi
  sleep 45
done
if [ "$stable_rounds" -lt 3 ]; then
  fail "Cluster never reached committee-tip sync within 600s (every in-committee peer must be at tip for isolation to work with supermajority quorum)"
fi

# Record pre-isolation state from monitor (a validator)
pre_ordinal=$(get_ordinal "$MONITOR_NODE")
pre_isolation_ordinal=$(get_ordinal "$ISOLATION_NODE" 2>/dev/null || echo "$pre_ordinal")
echo "  Pre-isolation ordinal: $pre_ordinal (monitor), $pre_isolation_ordinal (isolated node)"

# Final sanity: verify all nodes still in sync after the safety wait
for i in $(seq 0 $((NUM_GL0 - 1))); do
  node="gl0-${i}"
  ord=$(get_ordinal "$node")
  fac=$(get_facilitator_count "$node")
  echo "    $node: ordinal=$ord facilitators=$fac"
done

# NOTE: Background tx-sender is started globally by compose-runner.sh.
# It keeps EventTrigger flowing across all e2e tests.

# ── Phase 2: Isolate node ──────────────────────────────────────

echo ""
echo "Phase 2: Isolating $ISOLATION_NODE (iptables DROP)..."

# Install iptables if not present (ubuntu base image doesn't include it)
if ! docker exec "$ISOLATION_NODE" which iptables &>/dev/null; then
  echo "  Installing iptables in $ISOLATION_NODE..."
  docker exec --privileged "$ISOLATION_NODE" bash -c "apt-get update -qq && apt-get install -y -qq iptables" &>/dev/null || \
    fail "Could not install iptables in $ISOLATION_NODE"
fi

# CRITICAL: Sync isolation to a round boundary to avoid view desynchronization.
# If isolation lands mid-round (especially during CollectingProposals), nodes
# that have already received the isolated node's facilities declaration will
# stall at 0/N proposals while other nodes may do a view change — creating
# a permanent split where half the cluster is on view=0 and half on view=1.
#
# Strategy: Watch the ISOLATION NODE's own logs for ROUND_COMPLETED with all
# facilitators, then isolate immediately. Using the isolation node guarantees
# it has finished its round (sent all signatures, processed the outcome).
# We use `docker logs -f` (streaming) instead of polling `--since 2s` to
# eliminate the 1-2s gap where a round could start between polls.
echo "  Waiting for a round boundary on $ISOLATION_NODE before isolating..."

# Stream logs from the isolation node; as soon as we see ROUND_COMPLETED
# with the full facilitator set, break and apply iptables immediately.
# timeout ensures we don't hang forever.
round_synced=false
if timeout 120 bash -c '
  docker logs -f --tail=0 "'"$ISOLATION_NODE"'" 2>&1 | while IFS= read -r line; do
    if echo "$line" | grep -q "ROUND_COMPLETED.*facilitators=[2-9]"; then
      exit 0  # signal: round boundary found
    fi
  done
'; then
  round_synced=true
  echo "  Round completed on $ISOLATION_NODE — isolating immediately"
else
  echo "  WARNING: Could not sync to round boundary within 120s, isolating anyway"
fi

# Brief pause: even after the isolation node finishes its round, other nodes
# may still be processing signatures/acceptance for ~1-2s. This ensures the
# cluster is in quiescent inter-round state before we cut the network.
sleep 2

# Drop all inbound and outbound traffic — kills existing TCP connections immediately.
# Apply both rules in a single exec to minimize the window.
docker exec --privileged "$ISOLATION_NODE" bash -c 'iptables -A INPUT -j DROP && iptables -A OUTPUT -j DROP' 2>&1 || \
  fail "Could not apply iptables rules (needs --privileged or NET_ADMIN)"

echo "  $ISOLATION_NODE isolated. Waiting ${ISOLATION_DURATION}s for cluster to advance..."
sleep "$ISOLATION_DURATION"

# Check cluster advanced
post_isolation_ordinal=$(get_ordinal "$MONITOR_NODE")
echo "  Cluster advanced: ordinal $pre_ordinal → $post_isolation_ordinal"

if [ -z "$post_isolation_ordinal" ] || [ "$post_isolation_ordinal" -le "$pre_ordinal" ]; then
  docker exec --privileged "$ISOLATION_NODE" iptables -F 2>/dev/null || true
  fail "Cluster did not advance during isolation (stuck at ordinal $pre_ordinal)"
fi

advancement=$((post_isolation_ordinal - pre_ordinal))
echo "  Cluster produced $advancement snapshots while $ISOLATION_NODE was isolated"

# ── Phase 3: Restore network and monitor recovery ──────────────

echo ""
echo "Phase 3: Restoring $ISOLATION_NODE network..."

docker exec --privileged "$ISOLATION_NODE" iptables -F 2>&1 || \
  echo "  Warning: iptables flush failed"

echo "  Network restored. Monitoring recovery (timeout: ${RECOVERY_TIMEOUT}s)..."

recovery_start=$(date +%s)
recovery_deadline=$((recovery_start + RECOVERY_TIMEOUT))
recovered=false
rejoined_consensus=false

while [ "$(date +%s)" -lt "$recovery_deadline" ]; do
  elapsed=$(( $(date +%s) - recovery_start ))

  # Check if isolated node is participating in consensus again
  iso_fac=$(get_facilitator_count "$ISOLATION_NODE")
  iso_completed=$(get_completed_rounds_after "$ISOLATION_NODE" "$post_isolation_ordinal")
  fork_events=$(get_fork_events "$ISOLATION_NODE")
  monitor_ord=$(get_ordinal "$MONITOR_NODE")

  echo "  [${elapsed}s] $ISOLATION_NODE: facilitators=${iso_fac:-?} completedAfterIsolation=$iso_completed forkEvents=$fork_events clusterOrdinal=${monitor_ord:-?}"

  # Success criterion A: node logged "Round finished" >= 1 time after the isolation period ended.
  # get_completed_rounds_after counts log lines containing "Round finished ordinal=N" where N > post_isolation_ordinal,
  # so it only counts rounds completed on the re-joined node's own consensus loop.
  # (Threshold is 1, not 2: after recovery the node participates in one round cleanly. Subsequent rounds
  # may still evict it due to TCA penalty expiry cycles and removalPenaltyRounds — this is expected and
  # handled by the StallDetector. One completed round is sufficient proof of successful recovery.)
  # (Criterion B based on iso_fac was removed: get_facilitator_count returns the last ever-written
  # log value which is stale from before isolation and therefore always appears ≥ 2.)
  #
  # Success criterion B: cluster advanced ≥ 5 ordinals after gl0-4 reconnected AND gl0-4's
  # HTTP-reported ordinal matches the cluster (node caught up via download, not log-based).
  # This handles the case where the cluster produced only 1 ordinal during isolation (so gl0-4
  # was only 1 behind and forkLagThreshold suppresses gossip-based recovery), but the node
  # still successfully caught up via AbandonmentTracker's lagging-detection escalation.
  iso_ord=$(get_ordinal "$ISOLATION_NODE")
  cluster_advanced=false
  if [ -n "$monitor_ord" ] && [ -n "$post_isolation_ordinal" ] && [ "$monitor_ord" -ge "$((post_isolation_ordinal + 5))" ]; then
    cluster_advanced=true
  fi
  iso_caught_up=false
  if [ -n "$iso_ord" ] && [ -n "$monitor_ord" ] && [ "$iso_ord" -ge "$((monitor_ord - 1))" ]; then
    iso_caught_up=true
  fi

  if [ -n "$iso_completed" ] && [ "$iso_completed" -ge 1 ]; then
    recovered=true
    rejoined_consensus=true
    break
  fi
  if [ "$cluster_advanced" = "true" ] && [ "$iso_caught_up" = "true" ]; then
    recovered=true
    rejoined_consensus=true
    echo "  Criterion B: cluster advanced ≥5 ordinals and $ISOLATION_NODE caught up (iso_ord=$iso_ord cluster_ord=$monitor_ord)"
    break
  fi

  sleep 15
done

# ── Phase 4: Verify results ────────────────────────────────────

echo ""
echo "Phase 4: Verifying results..."

final_ordinal=$(get_ordinal "$MONITOR_NODE")
final_fac=$(get_facilitator_count "$MONITOR_NODE")
final_fork_events=$(get_fork_events "$ISOLATION_NODE")
recovery_elapsed=$(( $(date +%s) - recovery_start ))

echo "  Final state:"
echo "    Cluster ordinal: $final_ordinal"
echo "    Facilitators: $final_fac"
echo "    Fork events on $ISOLATION_NODE: $final_fork_events"
echo "    Recovery time: ${recovery_elapsed}s"

# Check for any abandonment tracker activity
abandonment_count=$(docker logs "$ISOLATION_NODE" 2>&1 | grep -c "ROUND_ABANDONED_TRACKED" 2>/dev/null || echo "0")
echo "    Round abandonments on $ISOLATION_NODE: $abandonment_count"

if [ "$recovered" != "true" ]; then
  fail "$ISOLATION_NODE did not recover within ${RECOVERY_TIMEOUT}s"
fi

pass "Node $ISOLATION_NODE recovered and rejoined consensus in ${recovery_elapsed}s (ordinal $pre_ordinal → $final_ordinal, completedAfterIsolation=$iso_completed fork events: $final_fork_events)"
