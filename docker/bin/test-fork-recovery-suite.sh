#!/usr/bin/env bash
#
# Fork Recovery Test Suite
# Runs the fork recovery matrix test across multiple cluster sizes and isolation counts.
# Each scenario spins up a fresh cluster, runs the test, and tears down.
#
# Usage: ./docker/bin/test-fork-recovery-suite.sh [--skip-assembly]
#
# Test matrix:
#   7 nodes: isolate 1, 2, 3
#   8 nodes: isolate 1, 2, 3
#

set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT"

SKIP_ASSEMBLY=false
if [ "$1" = "--skip-assembly" ]; then
  SKIP_ASSEMBLY=true
  shift
fi

RESULTS_DIR="/tmp/fork-recovery-suite-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RESULTS_DIR"

echo "============================================================"
echo "Fork Recovery Test Suite"
echo "============================================================"
echo "  Results dir: $RESULTS_DIR"
echo "  Skip assembly: $SKIP_ASSEMBLY"
echo ""

# Build JARs and Docker image once (unless --skip-assembly)
if [ "$SKIP_ASSEMBLY" = "false" ]; then
  echo "Building JARs and Docker image..."
  sbt assembly 2>&1 | tail -5
  docker build -t constellationnetwork/tessellation:test -f docker/Dockerfile . 2>&1 | tail -3
  echo "Build complete."
  echo ""
fi

# Test scenarios: "cluster_size:num_isolate"
SCENARIOS=(
  "7:1"
  "7:2"
  "7:3"
  "8:1"
  "8:2"
  "8:3"
)

declare -A RESULTS
TOTAL=0
PASSED=0
FAILED=0

for scenario in "${SCENARIOS[@]}"; do
  IFS=':' read -r cluster_size num_isolate <<< "$scenario"
  TOTAL=$((TOTAL + 1))
  test_name="${cluster_size}N_kill${num_isolate}"
  log_file="$RESULTS_DIR/${test_name}.log"

  echo ""
  echo "============================================================"
  echo "Scenario $TOTAL/${#SCENARIOS[@]}: ${cluster_size} nodes, isolate ${num_isolate}"
  echo "============================================================"

  # Tear down any existing cluster
  echo "  Cleaning up existing containers..."
  for i in $(seq 0 9); do
    docker kill "gl0-${i}" 2>/dev/null || true
    docker rm "gl0-${i}" 2>/dev/null || true
  done
  docker network rm tessellation_common 2>/dev/null || true
  sleep 2

  # Start cluster with compose-runner
  echo "  Starting ${cluster_size}-node cluster..."
  start_time=$(date +%s)

  # Use compose-runner in --up mode (no tests), then run our test separately
  # Pass --test=fork-recovery to avoid building snapshot-streaming (needs GITHUB_TOKEN)
  if bash docker/bin/compose-runner.sh \
    --num-gl0="$cluster_size" \
    --skip-assembly \
    --test=fork-recovery \
    --up \
    2>&1 | tee "$RESULTS_DIR/${test_name}_startup.log" | tail -5; then
    echo "  Cluster started."
  else
    echo "  ERROR: Cluster startup failed"
    RESULTS[$test_name]="FAIL (startup)"
    FAILED=$((FAILED + 1))
    continue
  fi

  # Run the matrix test
  echo "  Running fork recovery test..."
  if bash docker/bin/test-fork-recovery-matrix.sh "$cluster_size" "$num_isolate" 2>&1 | tee "$log_file"; then
    elapsed=$(( $(date +%s) - start_time ))
    RESULTS[$test_name]="PASS (${elapsed}s)"
    PASSED=$((PASSED + 1))
    echo "  ✅ PASSED in ${elapsed}s"
  else
    elapsed=$(( $(date +%s) - start_time ))
    RESULTS[$test_name]="FAIL (${elapsed}s)"
    FAILED=$((FAILED + 1))
    echo "  ❌ FAILED after ${elapsed}s (log: $log_file)"
  fi
done

# Final cleanup
echo ""
echo "Final cleanup..."
for i in $(seq 0 9); do
  docker kill "gl0-${i}" 2>/dev/null || true
  docker rm "gl0-${i}" 2>/dev/null || true
done
docker network rm tessellation_common 2>/dev/null || true

# Summary
echo ""
echo "============================================================"
echo "Fork Recovery Test Suite — Results"
echo "============================================================"
echo ""
printf "  %-20s %s\n" "SCENARIO" "RESULT"
printf "  %-20s %s\n" "--------" "------"
for scenario in "${SCENARIOS[@]}"; do
  IFS=':' read -r cs ni <<< "$scenario"
  test_name="${cs}N_kill${ni}"
  printf "  %-20s %s\n" "$test_name" "${RESULTS[$test_name]:-NOT RUN}"
done
echo ""
echo "  Total: $TOTAL  Passed: $PASSED  Failed: $FAILED"
echo "  Results: $RESULTS_DIR"
echo ""

if [ "$FAILED" -gt 0 ]; then
  echo "SUITE FAILED"
  exit 1
else
  echo "SUITE PASSED"
  exit 0
fi
