#!/usr/bin/env bash
# Run remote-compatible e2e test groups against a live network (testnet).
# Local proving ground for the future e2e-testnet.yml workflow: same env
# contract (TEST_PRIVATE_KEY_1..4 + *_URL), so migration is copy-paste.
#
# Usage:
#   FUNDER_PRIVATE_KEY=$(cat /tmp/freshseed.key) ./scripts/testnet-e2e/run-local.sh [group ...]
# Groups default to the GL0/GL1-only set. Metagraph groups (currency,
# token-locks, allow-spends, spend, rewards) additionally need ML0_URL/CL1_URL/
# DL1_URL + METAGRAPH_ID for a metagraph deployed on the target network.
set -euo pipefail
cd "$(dirname "$0")/../.."
export NODE_OPTIONS="${NODE_OPTIONS:-} --no-deprecation"
# Use the bundled node-key fixtures (delegated_staking/keys/*.hex), not docker paths.
export RUN_ENV="${RUN_ENV:-local}"

: "${FUNDER_PRIVATE_KEY:?set FUNDER_PRIVATE_KEY (hex) to a funded testnet wallet}"
export GL0_URL="${GL0_URL:-https://l0-lb-testnet.constellationnetwork.io}"
export GL1_URL="${GL1_URL:-https://l1-lb-testnet.constellationnetwork.io}"
export TEST_HOST="${TEST_HOST:-$GL0_URL}"
GROUPS_TO_RUN=("${@:-dag-cluster}")

# Key persistence (OUTSIDE the repo so funded keys can never be committed).
KEYS_DIR="${KEYS_DIR:-$HOME/.tessellation-testnet-e2e}"
mkdir -p "$KEYS_DIR"
export KEYS_FILE="$KEYS_DIR/keys.jsonl"     # full history, for sweeping
CURRENT_KEYS="$KEYS_DIR/current-keys.txt"   # last run's 4 keys, for reuse

# `sweep` mode: recover remaining funds from every recorded key, then exit.
if [[ "${1:-}" == "sweep" ]]; then
  node scripts/testnet-e2e/sweep-accounts.js
  exit $?
fi

# 1. Test keys: REUSE_KEYS=true reuses the previous run's funded keys
#    (skips most funding); default generates fresh ephemeral keys.
if [[ "${REUSE_KEYS:-true}" == "true" && -s "$CURRENT_KEYS" ]]; then
  i=1; while read -r k; do export "TEST_PRIVATE_KEY_$i=$k"; i=$((i+1)); done < "$CURRENT_KEYS"
  echo "reusing keys from $CURRENT_KEYS"
else
  : > "$CURRENT_KEYS"
  for i in 1 2 3 4; do
    k=$(node -e "const {dag4}=require('@stardust-collective/dag4');const a=dag4.createAccount();a.loginPrivateKey(dag4.keyStore.generatePrivateKey());console.log(a.keyTrio.privateKey)")
    export "TEST_PRIVATE_KEY_$i=$k"
    echo "$k" >> "$KEYS_FILE"; echo "$k" >> "$CURRENT_KEYS"
  done
  chmod 600 "$KEYS_FILE" "$CURRENT_KEYS"
  echo "generated 4 ephemeral test keys (recorded in $KEYS_FILE for later sweep)"
fi

# 2. Fund them from the funder wallet and wait for finalization.
FUNDER_PRIVATE_KEY="$FUNDER_PRIVATE_KEY" FUND_DAG="${FUND_DAG:-50}" node scripts/testnet-e2e/fund-accounts.js

# 3. Run the requested groups (same scripts the CI matrix runs).
# Positional port-prefix args are required by the scripts but only feed URL
# DEFAULTS -- the exported GL0_URL/GL1_URL/ML0_URL/CL1_URL/DL1_URL envs take
# precedence in shared/network.js. Arg shapes mirror compose-runner.sh.
declare -A SCRIPTS=(
  [dag-cluster]=".github/action_scripts/check_clusters/dag.js|90 91 92 93 94 false"
  [delegated-staking]=".github/action_scripts/delegated_staking/delegated-staking.js|90 91 testDelegatedStaking"
  [token-lock-replacement]=".github/action_scripts/delegated_staking/token-lock-replacement-edge-cases.js|90 91 testTokenLockReplacementEdgeCases"
  [currency]=".github/action_scripts/send_transactions/currency.js|90 91 92 93 94 false"
  [rewards]=".github/action_scripts/rewards.js|90 91 92 93 94 false"
  [token-locks]=".github/action_scripts/send_transactions/token-locks.js|90 91 92 93 94 false"
  [allow-spends]=".github/action_scripts/send_transactions/allow-spends-and-spend-transactions.js|90 91 92 93 94 false"
)
FAILED=()
for g in "${GROUPS_TO_RUN[@]}"; do
  entry="${SCRIPTS[$g]:-}"
  [[ -z "$entry" ]] && { echo "!! unknown/local-only group: $g (fork-recovery and snapshot-streaming cannot run remotely)"; FAILED+=("$g"); continue; }
  s="${entry%%|*}"; args="${entry#*|}"
  echo "=== RUNNING $g ==="
  # Some scripts log failures but still exit 0 -- treat failure markers in
  # output as failure too.
  rc=0
  out=$( (cd "$(dirname "$s")" && node "$(basename "$s")" $args) 2>&1 | tee /dev/stderr ) || rc=$?
  if [[ $rc -eq 0 ]] && ! grep -qE "workflow failed|Test failed" <<< "$out"; then
    echo "=== PASS $g ==="
  else
    echo "=== FAIL $g ==="; FAILED+=("$g")
  fi
done

[[ ${#FAILED[@]} -gt 0 ]] && { echo "FAILED: ${FAILED[*]}"; exit 1; }
echo "ALL PASSED: ${GROUPS_TO_RUN[*]}"
