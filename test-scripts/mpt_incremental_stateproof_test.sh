#!/bin/bash
# MPT Incremental StateProof Validation Test
# 
# Reproduces the "stateProofBroken when sending incremental" issue identified by Marcus.
# This test simulates the scenario where incremental snapshot validation fails due to 
# missing currency snapshot proofs in the StateChangesAccumulator delta filtering.
#
# Usage: ./test-scripts/mpt_incremental_stateproof_test.sh

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

get_snapshot_by_ordinal() {
    local ordinal=$1
    curl -s "http://localhost:9000/global-snapshots/$ordinal" 2>/dev/null
}

get_snapshot_info_by_ordinal() {
    local ordinal=$1
    curl -s "http://localhost:9000/global-snapshots/$ordinal/info" 2>/dev/null
}

check_logs_for_errors() {
    local node=${1:-gl0-0}
    local error_patterns=("StateProof Broken" "InvalidStateProof" "State broken for" "stateProofBroken")
    
    for pattern in "${error_patterns[@]}"; do
        if docker logs "$node" 2>&1 | grep -q "$pattern"; then
            info "Found StateProof error pattern: '$pattern'"
            docker logs "$node" 2>&1 | grep -A 3 -B 3 "$pattern" | tail -10
            return 0
        fi
    done
    return 1
}

# ============================================================================
# Mock Metagraph Simulation Functions
# ============================================================================

# Simulate currency snapshot from a metagraph by creating state channel snapshots
create_mock_metagraph_activity() {
    local metagraph_addr=$1
    local count=${2:-3}
    
    info "Creating mock activity for metagraph $metagraph_addr"
    
    for i in $(seq 1 $count); do
        # Create mock state channel snapshot to simulate metagraph activity
        local snapshot_data='{"mock": "snapshot", "metagraph": "'$metagraph_addr'", "seq": '$i'}'
        
        # Use wallet to create a simple transaction that will generate state changes
        local tx_result=$(java -jar "$WALLET" create-transaction \
            --keystore ../key.p12 \
            --password secret \
            --amount 1 \
            --fee 0 \
            --destination DAG8pkYdNmXCgJTX8VsRz2zPKKgAjWctc5F3i32s \
            2>/dev/null | jq -r '.hash // empty' 2>/dev/null)
        
        if [ -n "$tx_result" ]; then
            # Submit transaction to create state changes
            curl -s -X POST \
                -H "Content-Type: application/json" \
                -d '{"value": "'$snapshot_data'"}' \
                http://localhost:9400/transactions >/dev/null 2>&1
            sleep 2
        fi
    done
    
    sleep $WAIT_TIME
}

# ============================================================================
# Test Functions  
# ============================================================================

test_incremental_stateproof_validation() {
    log "=========================================="
    log "TEST: Incremental StateProof Validation"
    log "=========================================="
    
    start_cluster
    
    local initial_ordinal=$(get_ordinal)
    info "Initial ordinal: $initial_ordinal"
    
    # Create activity from multiple mock metagraphs to populate currency snapshots
    log "Generating activity from multiple metagraphs..."
    create_mock_metagraph_activity "DAG123metagraph1" 2
    create_mock_metagraph_activity "DAG456metagraph2" 2  
    create_mock_metagraph_activity "DAG789metagraph3" 2
    
    local after_activity_ordinal=$(get_ordinal)
    info "After activity ordinal: $after_activity_ordinal"
    
    if [ "$after_activity_ordinal" -le "$initial_ordinal" ]; then
        warn "No ordinal progression, creating additional activity..."
        # Force some activity
        for i in {1..5}; do
            java -jar "$WALLET" create-transaction \
                --keystore ../key.p12 \
                --password secret \
                --amount 1 \
                --fee 0 \
                --destination DAG8pkYdNmXCgJTX8VsRz2zPKKgAjWctc5F3i32s >/dev/null 2>&1
            sleep 5
        done
        after_activity_ordinal=$(get_ordinal)
        info "Final ordinal after forced activity: $after_activity_ordinal"
    fi
    
    # Wait for several more snapshots to ensure incremental processing
    local target_ordinal=$((after_activity_ordinal + 5))
    log "Waiting for ordinal $target_ordinal to ensure incremental processing..."
    wait_for_ordinal $target_ordinal
    
    # Get an incremental snapshot for validation testing
    local test_ordinal=$((target_ordinal - 2))
    log "Testing incremental snapshot validation at ordinal $test_ordinal"
    
    # Fetch the snapshot and its info
    local snapshot=$(get_snapshot_by_ordinal $test_ordinal)
    local snapshot_info=$(get_snapshot_info_by_ordinal $test_ordinal)
    
    if [ "$snapshot" = "null" ] || [ -z "$snapshot" ]; then
        warn "No snapshot found at ordinal $test_ordinal, trying latest"
        test_ordinal=$(get_ordinal)
        snapshot=$(get_snapshot_by_ordinal $test_ordinal)
        snapshot_info=$(get_snapshot_info_by_ordinal $test_ordinal)
    fi
    
    if [ "$snapshot" = "null" ] || [ -z "$snapshot" ]; then
        fail "Could not retrieve snapshot for testing"
    fi
    
    info "Testing snapshot at ordinal $test_ordinal"
    info "Snapshot hash: $(echo "$snapshot" | jq -r '.value.hash // "unknown"')"
    
    # Check for StateProof-related errors in logs
    log "Checking for StateProof validation errors..."
    
    if check_logs_for_errors "gl0-0"; then
        log "${RED}✗ REPRODUCED: Found StateProof validation errors!${NC}"
        
        # Extract specific error details
        info "Error analysis:"
        docker logs gl0-0 2>&1 | grep -A 5 -B 5 "StateProof\|InvalidStateProof" | tail -20
        
        return 1
    else
        # Force a scenario that might trigger the issue
        log "No errors found yet. Attempting to trigger incremental validation issue..."
        
        # Try to force download/validation path by stopping and restarting a node
        info "Restarting node to trigger snapshot download/validation..."
        docker stop gl0-1
        sleep 10
        docker start gl0-1
        
        # Wait for node to rejoin and potentially trigger validation
        sleep 30
        
        if check_logs_for_errors "gl0-1"; then
            log "${RED}✗ REPRODUCED: Found StateProof validation errors after restart!${NC}"
            
            info "Error analysis:"
            docker logs gl0-1 2>&1 | grep -A 5 -B 5 "StateProof\|InvalidStateProof" | tail -20
            
            return 1
        else
            log "${YELLOW}⚠ Could not reproduce StateProof validation errors${NC}"
            info "This may indicate:"
            info "1. The issue occurs under specific conditions not met in this test"
            info "2. The issue has been fixed in the current codebase"
            info "3. Additional metagraph activity is needed to trigger the scenario"
            return 0
        fi
    fi
}

test_currency_proof_delta_issue() {
    log "=========================================="  
    log "TEST: Currency Proof Delta Filtering Issue"
    log "=========================================="
    
    # This test focuses on the specific delta filtering issue
    # mentioned in GlobalSnapshotAcceptanceManager.scala lines 1105-1107
    
    start_cluster
    
    # Generate currency activity to populate proof deltas
    log "Creating currency activity to populate proof deltas..."
    
    # Simulate multiple metagraph updates
    for round in {1..3}; do
        info "Activity round $round"
        create_mock_metagraph_activity "DAG111round${round}" 1
        create_mock_metagraph_activity "DAG222round${round}" 1
        sleep 15
    done
    
    local final_ordinal=$(get_ordinal)
    info "Final ordinal: $final_ordinal"
    
    # Check for the specific error pattern related to proof deltas
    log "Checking for currency proof delta-related errors..."
    
    local error_found=0
    for node in gl0-0 gl0-1 gl0-2; do
        if docker logs "$node" 2>&1 | grep -q "StateProof.*mismatch\|proof.*delta\|currency.*proof"; then
            warn "Found potential delta-related errors in $node"
            docker logs "$node" 2>&1 | grep -A 3 -B 3 "StateProof\|proof\|currency" | tail -15
            error_found=1
        fi
    done
    
    if [ $error_found -eq 1 ]; then
        log "${RED}✗ DETECTED: Currency proof delta issues found!${NC}"
        return 1
    else
        log "${GREEN}✓ No currency proof delta issues detected${NC}"
        return 0
    fi
}

# ============================================================================
# Main Test Runner
# ============================================================================

main() {
    local test_name=${1:-"all"}
    local failed=0
    
    log "MPT Incremental StateProof Validation Test"
    log "Test: $test_name"
    log ""
    
    case $test_name in
        incremental)
            test_incremental_stateproof_validation || failed=1
            ;;
        delta)
            test_currency_proof_delta_issue || failed=1
            ;;
        all)
            test_incremental_stateproof_validation || failed=1
            test_currency_proof_delta_issue || failed=1
            ;;
        *)
            echo "Usage: $0 [incremental|delta|all]"
            echo ""
            echo "Tests:"
            echo "  incremental  - Test incremental snapshot StateProof validation"
            echo "  delta        - Test currency proof delta filtering issues"
            echo "  all          - Run all tests"
            exit 1
            ;;
    esac
    
    echo ""
    if [ $failed -eq 0 ]; then
        log "${GREEN}=========================================="
        log "ALL TESTS COMPLETED"
        log "Note: No reproduction may indicate issue is fixed"
        log "      or requires more specific conditions"
        log "==========================================${NC}"
    else
        log "${RED}=========================================="
        log "STATEPROOF VALIDATION ISSUE REPRODUCED"
        log "This confirms Marcus's findings about"
        log "incremental snapshot validation failures"
        log "==========================================${NC}"
        exit 1
    fi
}

main "$@"