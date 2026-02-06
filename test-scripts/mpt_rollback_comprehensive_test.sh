#!/bin/bash
# MPT Rollback Comprehensive E2E Test
# 
# Tests StateProof validity with exotic data scenarios:
# - Multiple addresses with stakes
# - Token lock replacements
# - Stake withdrawals  
# - Accumulated rewards
# - Full resync from scratch
# - Streaming node catch-up
#
# Usage: ./test-scripts/mpt_rollback_comprehensive_test.sh [test_name]

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(dirname "$SCRIPT_DIR")"
cd "$REPO_DIR"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Config
WALLET="docker/jars/wallet.jar"
WAIT_TIME=30

log() { echo -e "${GREEN}[TEST]${NC} $1"; }
info() { echo -e "${BLUE}[INFO]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
fail() { echo -e "${RED}[FAIL]${NC} $1"; exit 1; }

# ============================================================================
# Utility Functions
# ============================================================================

wait_for_cluster() {
    log "Waiting for cluster to be healthy..."
    local timeout=180
    local start=$(date +%s)
    while true; do
        local healthy=$(docker ps --filter "health=healthy" --format "{{.Names}}" 2>/dev/null | wc -l)
        if [ "$healthy" -ge 6 ]; then
            log "Cluster healthy ($healthy nodes)"
            return 0
        fi
        if [ $(($(date +%s) - start)) -gt $timeout ]; then
            fail "Timeout waiting for cluster"
        fi
        sleep 5
    done
}

cleanup_cluster() {
    log "Cleaning up cluster..."
    docker stop $(docker ps -q) 2>/dev/null || true
    docker rm $(docker ps -aq) 2>/dev/null || true
    docker network rm tessellation_common 0_default 1_default 2_default 2>/dev/null || true
    for i in 0 1 2; do
        docker run --rm -v "$REPO_DIR/nodes/$i:/node" alpine sh -c "rm -rf /node/*" 2>/dev/null || true
    done
}

start_cluster() {
    cleanup_cluster
    log "Starting fresh cluster..."
    SKIP_ASSEMBLY=true bash ./docker/bin/compose-runner.sh >/dev/null 2>&1 &
    wait_for_cluster
    sleep 15
}

get_ordinal() {
    local result
    result=$(curl -s http://localhost:9000/global-snapshots/latest 2>/dev/null | jq -r '.value.ordinal // empty' 2>/dev/null)
    # Return 0 if result is empty or not a number
    if [[ -z "$result" ]] || ! [[ "$result" =~ ^[0-9]+$ ]]; then
        echo "0"
    else
        echo "$result"
    fi
}

get_latest_hash() {
    curl -s http://localhost:9000/global-snapshots/latest 2>/dev/null | jq -r '.value.lastSnapshotHash // ""'
}

wait_for_ordinal() {
    local target=$1
    local timeout=${2:-120}
    local start=$(date +%s)
    while true; do
        local current=$(get_ordinal)
        if [ "$current" -ge "$target" ]; then
            echo "$current"
            return 0
        fi
        if [ $(($(date +%s) - start)) -gt $timeout ]; then
            fail "Timeout waiting for ordinal $target (current: $current)"
        fi
        sleep 2
    done
}

# Create token lock for a node wallet
create_token_lock() {
    local node_num=$1
    local amount=$2
    local parent_file=$3
    local replace_hash=$4
    
    cd nodes/$node_num
    source .envrc
    
    local cmd="java -jar ../../$WALLET create-token-lock --amount $amount"
    [ -n "$parent_file" ] && cmd="$cmd --parent $parent_file"
    [ -n "$replace_hash" ] && cmd="$cmd --replace $replace_hash"
    
    local hash=$($cmd 2>/dev/null)
    curl -s -X POST -H 'Content-Type: application/json' -d @event http://localhost:9100/token-locks >/dev/null
    cd "$REPO_DIR"
    echo "$hash"
}

# Create delegated stake
create_delegated_stake() {
    local node_num=$1
    local amount=$2
    local tl_hash=$3
    local target_node_id=$4
    local parent_file=$5
    
    cd nodes/$node_num
    source .envrc
    
    local cmd="java -jar ../../$WALLET create-delegated-stake --amount $amount --token-lock $tl_hash"
    [ -n "$target_node_id" ] && cmd="$cmd --nodeId $target_node_id"
    [ -n "$parent_file" ] && cmd="$cmd --parent $parent_file"
    
    local hash=$($cmd 2>/dev/null)
    curl -s -X POST -H 'Content-Type: application/json' -d @event http://localhost:9000/delegated-stakes >/dev/null
    cd "$REPO_DIR"
    echo "$hash"
}

# Withdraw delegated stake
withdraw_stake() {
    local node_num=$1
    local stake_hash=$2
    
    cd nodes/$node_num
    source .envrc
    
    local hash=$(java -jar ../../$WALLET withdraw-delegated-stake --stake $stake_hash 2>/dev/null)
    curl -s -X PUT -H 'Content-Type: application/json' -d @event http://localhost:9000/delegated-stakes >/dev/null
    cd "$REPO_DIR"
    echo "$hash"
}

# Get last reference for token locks
get_tl_ref() {
    local address=$1
    curl -s "http://localhost:9100/token-locks/last-reference/$address"
}

# Get last reference for delegated stakes  
get_ds_ref() {
    local address=$1
    curl -s "http://localhost:9000/delegated-stakes/last-reference/$address"
}

# Get address for a node
get_address() {
    local node_num=$1
    cat nodes/$node_num/address
}

# Get peer ID for a node
get_peer_id() {
    local node_num=$1
    cat nodes/$node_num/peer_id
}

# Get current state summary
get_state_summary() {
    curl -s http://localhost:9000/global-snapshots/latest/combined | jq '{
        ordinal: .[0].ordinal,
        addresses_with_stakes: (.[1].activeDelegatedStakes | keys | length),
        total_stakes: ([.[1].activeDelegatedStakes | to_entries[] | .value | length] | add // 0),
        total_token_locks: ([.[1].activeTokenLocks | to_entries[] | .value | length] | add // 0)
    }'
}

# Delete ordinals from a node
delete_ordinals() {
    local node_num=$1
    local start=$2
    local end=$3
    
    info "Deleting ordinals $start-$end from node $node_num..."
    docker run --rm -v "$REPO_DIR/nodes/$node_num/gl0-data:/data" alpine sh -c \
        "for i in \$(seq $start $end); do rm -f /data/snapshot_info/\$i /data/mpt_snapshot_info/\$i /data/incremental_snapshot/ordinal/0/\$i 2>/dev/null; done"
}

# Clear all data from a node
clear_node_data() {
    local node_num=$1
    info "Clearing all data from node $node_num..."
    docker run --rm -v "$REPO_DIR/nodes/$node_num/gl0-data:/data" alpine sh -c \
        "rm -rf /data/snapshot_info/* /data/mpt_snapshot_info/* /data/incremental_snapshot/* 2>/dev/null || true"
}

# Setup rollback env
setup_rollback() {
    local node_num=$1
    local hash=$2
    
    sed -i '/CL_DOCKER_ROLLBACK/d' nodes/$node_num/.env 2>/dev/null || true
    echo "CL_DOCKER_ROLLBACK=true" >> nodes/$node_num/.env
    echo "CL_DOCKER_ROLLBACK_HASH=$hash" >> nodes/$node_num/.env
}

# Restart a node
restart_node() {
    local node_num=$1
    local container="gl0-$node_num"
    
    info "Restarting $container..."
    docker rm $container 2>/dev/null || true
    docker compose -f nodes/$node_num/docker-compose.yaml up -d gl0
    sleep 60
}

# Check for StateProof errors
check_errors() {
    local container=$1
    local errors=$(docker logs $container 2>&1 | grep -iE "stateproof.*broken|broken.*stateproof" | head -5)
    if [ -n "$errors" ]; then
        echo "$errors"
        return 1
    fi
    return 0
}

# ============================================================================
# TEST: Multi-Address Stakes
# Multiple addresses each creating stakes to different nodes
# ============================================================================
test_multi_address_stakes() {
    log "=========================================="
    log "TEST: Multi-Address Stakes"
    log "=========================================="
    
    start_cluster
    
    local addr0=$(get_address 0)
    local addr1=$(get_address 1)
    local addr2=$(get_address 2)
    local peer0=$(get_peer_id 0)
    local peer1=$(get_peer_id 1)
    local peer2=$(get_peer_id 2)
    
    info "Addresses: $addr0, $addr1, $addr2"
    
    # Node 0: Create stake to node 1
    log "Node 0 -> Stake to Node 1"
    local tl0=$(create_token_lock 0 6000)
    sleep $WAIT_TIME
    local ds0=$(create_delegated_stake 0 6000 "$tl0" "$peer1")
    sleep $WAIT_TIME
    
    # Node 1: Create stake to node 2
    log "Node 1 -> Stake to Node 2"
    local tl1=$(create_token_lock 1 6000)
    sleep $WAIT_TIME
    local ds1=$(create_delegated_stake 1 6000 "$tl1" "$peer2")
    sleep $WAIT_TIME
    
    # Node 2: Create stake to node 0
    log "Node 2 -> Stake to Node 0"
    local tl2=$(create_token_lock 2 6000)
    sleep $WAIT_TIME
    local ds2=$(create_delegated_stake 2 6000 "$tl2" "$peer0")
    sleep $WAIT_TIME
    
    local before_ordinal=$(get_ordinal)
    info "State before rollback:"
    get_state_summary
    
    # Get rollback hash
    local hash=$(get_latest_hash)
    
    # Stop and clear MPT on node 0
    docker stop gl0-0
    docker run --rm -v "$REPO_DIR/nodes/0/gl0-data:/data" alpine sh -c "rm -rf /data/mpt_snapshot_info/*"
    
    # Rollback
    setup_rollback 0 "$hash"
    restart_node 0
    
    # Wait for sync
    wait_for_ordinal $((before_ordinal + 1))
    
    # Check errors
    if check_errors "gl0-0"; then
        log "${GREEN}✅ PASSED: Multi-Address Stakes${NC}"
    else
        fail "StateProof errors found!"
    fi
}

# ============================================================================
# TEST: Stake Lifecycle (Create -> Accumulate Rewards -> Withdraw)
# ============================================================================
test_stake_lifecycle() {
    log "=========================================="
    log "TEST: Stake Lifecycle"
    log "=========================================="
    
    start_cluster
    
    local peer1=$(get_peer_id 1)
    
    # Create stake
    log "Creating stake..."
    local tl=$(create_token_lock 0 6000)
    sleep $WAIT_TIME
    local ds=$(create_delegated_stake 0 6000 "$tl" "$peer1")
    sleep $WAIT_TIME
    
    local create_ordinal=$(get_ordinal)
    info "Stake created at ordinal ~$create_ordinal"
    
    # Wait for rewards to accumulate (several ordinals)
    log "Waiting for rewards to accumulate..."
    wait_for_ordinal $((create_ordinal + 10))
    
    local mid_ordinal=$(get_ordinal)
    local mid_hash=$(get_latest_hash)
    info "Mid-point ordinal: $mid_ordinal"
    
    # Withdraw stake
    log "Withdrawing stake..."
    local withdraw_hash=$(withdraw_stake 0 "$ds")
    sleep $WAIT_TIME
    
    local after_ordinal=$(get_ordinal)
    info "After withdrawal ordinal: $after_ordinal"
    
    # Rollback to mid-point (before withdrawal)
    docker stop gl0-0
    delete_ordinals 0 $((mid_ordinal + 1)) $((after_ordinal + 5))
    
    setup_rollback 0 "$mid_hash"
    restart_node 0
    
    wait_for_ordinal $((after_ordinal + 1))
    
    if check_errors "gl0-0"; then
        log "${GREEN}✅ PASSED: Stake Lifecycle${NC}"
    else
        fail "StateProof errors found!"
    fi
}

# ============================================================================
# TEST: Token Lock Replacement Chain
# Multiple sequential replacements
# ============================================================================
test_replacement_chain() {
    log "=========================================="
    log "TEST: Token Lock Replacement Chain"
    log "=========================================="
    
    start_cluster
    
    local addr0=$(get_address 0)
    local peer1=$(get_peer_id 1)
    
    # Create initial stake
    log "Creating initial stake..."
    local tl1=$(create_token_lock 0 6000)
    sleep $WAIT_TIME
    local ds=$(create_delegated_stake 0 6000 "$tl1" "$peer1")
    sleep $WAIT_TIME
    
    local ordinal1=$(get_ordinal)
    local hash1=$(get_latest_hash)
    info "After TL#1: ordinal $ordinal1"
    
    # Replacement #1
    log "Replacement #1 (6000 -> 7000)..."
    get_tl_ref "$addr0" > nodes/0/tl-ref.json
    local tl2=$(create_token_lock 0 7000 "nodes/0/tl-ref.json" "$tl1")
    sleep $WAIT_TIME
    
    local ordinal2=$(get_ordinal)
    local hash2=$(get_latest_hash)
    info "After TL#2: ordinal $ordinal2"
    
    # Replacement #2
    log "Replacement #2 (7000 -> 8000)..."
    get_tl_ref "$addr0" > nodes/0/tl-ref.json
    local tl3=$(create_token_lock 0 8000 "nodes/0/tl-ref.json" "$tl2")
    sleep $WAIT_TIME
    
    local ordinal3=$(get_ordinal)
    info "After TL#3: ordinal $ordinal3"
    
    # Rollback to after TL#1 but before TL#2
    docker stop gl0-0
    delete_ordinals 0 $((ordinal1 + 1)) $((ordinal3 + 5))
    
    setup_rollback 0 "$hash1"
    restart_node 0
    
    wait_for_ordinal $((ordinal3 + 1))
    
    if check_errors "gl0-0"; then
        log "${GREEN}✅ PASSED: Token Lock Replacement Chain${NC}"
    else
        fail "StateProof errors found!"
    fi
}

# ============================================================================
# TEST: Full Resync from Scratch
# Clear all data and resync from network
# ============================================================================
test_full_resync() {
    log "=========================================="
    log "TEST: Full Resync from Scratch"
    log "=========================================="
    
    start_cluster
    
    local peer1=$(get_peer_id 1)
    local peer2=$(get_peer_id 2)
    
    # Create multiple stakes
    log "Creating multiple stakes..."
    local tl1=$(create_token_lock 0 6000)
    sleep $WAIT_TIME
    local ds1=$(create_delegated_stake 0 6000 "$tl1" "$peer1")
    sleep $WAIT_TIME
    
    get_tl_ref "$(get_address 0)" > nodes/0/tl-ref.json
    local tl2=$(create_token_lock 0 6000 "nodes/0/tl-ref.json")
    sleep $WAIT_TIME
    
    get_ds_ref "$(get_address 0)" > nodes/0/ds-ref.json
    local ds2=$(create_delegated_stake 0 6000 "$tl2" "$peer2" "nodes/0/ds-ref.json")
    sleep $WAIT_TIME
    
    local target_ordinal=$(get_ordinal)
    info "Target ordinal: $target_ordinal"
    info "State:"
    get_state_summary
    
    # Stop node 2 and clear ALL data
    docker stop gl0-2
    clear_node_data 2
    
    # Also clear the .env rollback settings
    sed -i '/CL_DOCKER_ROLLBACK/d' nodes/2/.env 2>/dev/null || true
    
    # Restart - should sync from network
    info "Restarting node 2 for full resync..."
    docker rm gl0-2 2>/dev/null || true
    docker compose -f nodes/2/docker-compose.yaml up -d gl0
    
    # Wait for it to catch up
    sleep 90
    
    # Check if it synced properly
    local node2_ordinal=$(curl -s http://localhost:9020/global-snapshots/latest 2>/dev/null | jq -r '.value.ordinal // 0')
    info "Node 2 ordinal after resync: $node2_ordinal"
    
    if [ "$node2_ordinal" -ge "$target_ordinal" ]; then
        if check_errors "gl0-2"; then
            log "${GREEN}✅ PASSED: Full Resync from Scratch${NC}"
        else
            fail "StateProof errors found during resync!"
        fi
    else
        fail "Node 2 failed to sync to target ordinal"
    fi
}

# ============================================================================
# TEST: Streaming Node Catch-up
# Simulate a streaming node that falls behind and catches up
# ============================================================================
test_streaming_catchup() {
    log "=========================================="
    log "TEST: Streaming Node Catch-up"
    log "=========================================="
    
    start_cluster
    
    local peer1=$(get_peer_id 1)
    
    # Create initial state
    log "Creating initial state..."
    local tl1=$(create_token_lock 0 6000)
    sleep $WAIT_TIME
    local ds1=$(create_delegated_stake 0 6000 "$tl1" "$peer1")
    sleep $WAIT_TIME
    
    local checkpoint_ordinal=$(get_ordinal)
    local checkpoint_hash=$(get_latest_hash)
    info "Checkpoint ordinal: $checkpoint_ordinal"
    
    # Stop node 2 (simulates streaming node falling behind)
    docker stop gl0-2
    
    # Create more state while node 2 is down
    log "Creating more state while node 2 is down..."
    get_tl_ref "$(get_address 0)" > nodes/0/tl-ref.json
    local tl2=$(create_token_lock 0 7000 "nodes/0/tl-ref.json" "$tl1")
    sleep $WAIT_TIME
    
    local final_ordinal=$(get_ordinal)
    info "Final ordinal: $final_ordinal"
    
    # Clear node 2 data back to checkpoint
    delete_ordinals 2 $((checkpoint_ordinal + 1)) $((final_ordinal + 10))
    
    # Restart node 2 with rollback to checkpoint
    setup_rollback 2 "$checkpoint_hash"
    restart_node 2
    
    # Wait for catch-up
    sleep 60
    
    local node2_ordinal=$(curl -s http://localhost:9020/global-snapshots/latest 2>/dev/null | jq -r '.value.ordinal // 0')
    info "Node 2 ordinal after catch-up: $node2_ordinal"
    
    if [ "$node2_ordinal" -ge "$final_ordinal" ]; then
        if check_errors "gl0-2"; then
            log "${GREEN}✅ PASSED: Streaming Node Catch-up${NC}"
        else
            fail "StateProof errors found during catch-up!"
        fi
    else
        fail "Node 2 failed to catch up"
    fi
}

# ============================================================================
# TEST: Mixed Operations Rollback
# Create, replace, withdraw all in sequence, then rollback to middle
# ============================================================================
test_mixed_operations() {
    log "=========================================="
    log "TEST: Mixed Operations Rollback"
    log "=========================================="
    
    start_cluster
    
    local addr0=$(get_address 0)
    local peer1=$(get_peer_id 1)
    local peer2=$(get_peer_id 2)
    
    # Phase 1: Create two stakes
    log "Phase 1: Creating two stakes..."
    local tl1=$(create_token_lock 0 6000)
    sleep $WAIT_TIME
    local ds1=$(create_delegated_stake 0 6000 "$tl1" "$peer1")
    sleep $WAIT_TIME
    
    get_tl_ref "$addr0" > nodes/0/tl-ref.json
    local tl2=$(create_token_lock 0 6000 "nodes/0/tl-ref.json")
    sleep $WAIT_TIME
    
    get_ds_ref "$addr0" > nodes/0/ds-ref.json
    local ds2=$(create_delegated_stake 0 6000 "$tl2" "$peer2" "nodes/0/ds-ref.json")
    sleep $WAIT_TIME
    
    local phase1_ordinal=$(get_ordinal)
    local phase1_hash=$(get_latest_hash)
    info "Phase 1 complete at ordinal $phase1_ordinal"
    
    # Phase 2: Replace TL1, withdraw DS2
    log "Phase 2: Replace TL1, withdraw DS2..."
    get_tl_ref "$addr0" > nodes/0/tl-ref.json
    local tl3=$(create_token_lock 0 8000 "nodes/0/tl-ref.json" "$tl1")
    sleep $WAIT_TIME
    
    local withdraw_hash=$(withdraw_stake 0 "$ds2")
    sleep $WAIT_TIME
    
    local phase2_ordinal=$(get_ordinal)
    info "Phase 2 complete at ordinal $phase2_ordinal"
    
    # Phase 3: Create new stake with new TL
    log "Phase 3: Create new stake..."
    get_tl_ref "$addr0" > nodes/0/tl-ref.json
    local tl4=$(create_token_lock 0 6000 "nodes/0/tl-ref.json")
    sleep $WAIT_TIME
    
    get_ds_ref "$addr0" > nodes/0/ds-ref.json
    local ds3=$(create_delegated_stake 0 6000 "$tl4" "$peer2" "nodes/0/ds-ref.json")
    sleep $WAIT_TIME
    
    local phase3_ordinal=$(get_ordinal)
    info "Phase 3 complete at ordinal $phase3_ordinal"
    info "Final state:"
    get_state_summary
    
    # Rollback to end of Phase 1
    docker stop gl0-0
    delete_ordinals 0 $((phase1_ordinal + 1)) $((phase3_ordinal + 5))
    
    setup_rollback 0 "$phase1_hash"
    restart_node 0
    
    wait_for_ordinal $((phase3_ordinal + 1))
    
    if check_errors "gl0-0"; then
        log "${GREEN}✅ PASSED: Mixed Operations Rollback${NC}"
    else
        fail "StateProof errors found!"
    fi
}

# ============================================================================
# Main
# ============================================================================
main() {
    local test_name=${1:-all}
    local failed=0
    
    log "MPT Rollback Comprehensive Test Suite"
    log "Test: $test_name"
    log ""
    
    case $test_name in
        multi_address)
            test_multi_address_stakes || failed=1
            ;;
        lifecycle)
            test_stake_lifecycle || failed=1
            ;;
        replacement_chain)
            test_replacement_chain || failed=1
            ;;
        full_resync)
            test_full_resync || failed=1
            ;;
        streaming)
            test_streaming_catchup || failed=1
            ;;
        mixed)
            test_mixed_operations || failed=1
            ;;
        all)
            test_multi_address_stakes || failed=1
            test_stake_lifecycle || failed=1
            test_replacement_chain || failed=1
            test_full_resync || failed=1
            test_streaming_catchup || failed=1
            test_mixed_operations || failed=1
            ;;
        *)
            echo "Usage: $0 [multi_address|lifecycle|replacement_chain|full_resync|streaming|mixed|all]"
            exit 1
            ;;
    esac
    
    echo ""
    if [ $failed -eq 0 ]; then
        log "${GREEN}=========================================="
        log "ALL TESTS PASSED"
        log "==========================================${NC}"
    else
        log "${RED}=========================================="
        log "SOME TESTS FAILED"
        log "==========================================${NC}"
        exit 1
    fi
}

main "$@"
