#!/usr/bin/env bash
# committee_rewards.selftest.sh -- offline self-test for committee_rewards.js.
#
# Runs the real test script against a synthetic snapshot window in both directions and asserts BOTH
# outcomes:
#   1. correct behavior            -> exit 0
#   2. legacy evidence-score filter -> exit non-zero, naming the unpaid Tier-1 climber
#
# (2) is the point. A test that only passes proves its assertions did not fire; this proves they CAN
# fire on the specific behavior they target. It also catches the class of defect `node --check`
# cannot see -- an undefined identifier shipped past two review rounds before this existed.
#
# Takes ~2 seconds, needs no docker and no cluster. Run it after ANY change to committee_rewards.js.
# Wired into the committee-rewards CI job so the fixture cannot rot alongside the script.
#
# Usage: .github/action_scripts/committee_rewards.selftest.sh

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXTURE="$SCRIPT_DIR/committee_rewards.fixture.js"
TEST_SCRIPT="$SCRIPT_DIR/committee_rewards.js"
EXPECTED_ERROR='seated Tier-1 facilitator'

WORKSPACE="$(mktemp -d)"
PASS_PORT=${COMMITTEE_SELFTEST_PORT:-18131}
FAIL_PORT=$((PASS_PORT + 1))
PASS_SRV=""
FAIL_SRV=""

cleanup() {
  [ -n "$PASS_SRV" ] && kill "$PASS_SRV" 2>/dev/null
  [ -n "$FAIL_SRV" ] && kill "$FAIL_SRV" 2>/dev/null
  rm -rf "$WORKSPACE"
}
trap cleanup EXIT

fail() {
  echo "SELFTEST FAILED: $*" >&2
  exit 1
}

# Isolated workspace so the fabricated nodes/<i>/{peer_id,address} cannot collide with a real
# cluster's key material. `shared` and `node_modules` are symlinked, so the script under test runs
# with exactly the dependencies it will have in CI.
node "$FIXTURE" --write-node-fixtures "$WORKSPACE" >/dev/null || fail "could not write node fixtures"
mkdir -p "$WORKSPACE/.github/action_scripts"
ln -s "$SCRIPT_DIR/shared" "$WORKSPACE/.github/action_scripts/shared"
ln -s "$SCRIPT_DIR/node_modules" "$WORKSPACE/.github/action_scripts/node_modules"
cp "$TEST_SCRIPT" "$WORKSPACE/.github/action_scripts/"

wait_for_port() {
  local port=$1
  for _ in $(seq 1 40); do
    if curl -sf "http://localhost:$port/global-snapshots/latest" >/dev/null 2>&1; then return 0; fi
    sleep 0.25
  done
  return 1
}

run_case() {
  local port=$1 out=$2
  ( cd "$WORKSPACE/.github/action_scripts" \
      && GL0_URL="http://localhost:$port" NUM_GL0_NODES=5 NUM_GL0_EARLY=3 \
         timeout 120 node committee_rewards.js 90 91 ) >"$out" 2>&1
  echo $?
}

echo "=== case 1/2: correct behavior (expect exit 0)"
node "$FIXTURE" "$PASS_PORT" >"$WORKSPACE/pass-server.log" 2>&1 &
PASS_SRV=$!
wait_for_port "$PASS_PORT" || fail "fixture server did not come up on $PASS_PORT"
PASS_RC=$(run_case "$PASS_PORT" "$WORKSPACE/pass.out")
if [ "$PASS_RC" != "0" ]; then
  cat "$WORKSPACE/pass.out" >&2
  fail "expected exit 0 against the correct fixture, got $PASS_RC"
fi
grep -q 'Tier-1 seat(s) absent from the last' "$WORKSPACE/pass.out" \
  || fail "pass case did not report a Tier-1 seat (the assertion may be vacuous)"
echo "    ok: exit 0 and a Tier-1 seat was exercised"

echo "=== case 2/2: legacy evidence-score filter (expect non-zero)"
node "$FIXTURE" "$FAIL_PORT" --drop-climber-reward >"$WORKSPACE/fail-server.log" 2>&1 &
FAIL_SRV=$!
wait_for_port "$FAIL_PORT" || fail "fixture server did not come up on $FAIL_PORT"
FAIL_RC=$(run_case "$FAIL_PORT" "$WORKSPACE/fail.out")
if [ "$FAIL_RC" = "0" ]; then
  cat "$WORKSPACE/fail.out" >&2
  fail "test PASSED against the legacy filter -- the assertions have no teeth"
fi
grep -q "$EXPECTED_ERROR" "$WORKSPACE/fail.out" \
  || { cat "$WORKSPACE/fail.out" >&2; fail "failed for the wrong reason (expected '$EXPECTED_ERROR')"; }
echo "    ok: exit $FAIL_RC naming the unpaid Tier-1 climber"

echo "=== committee_rewards selftest PASSED"
