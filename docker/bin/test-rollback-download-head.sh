#!/usr/bin/env bash
#
# Controlled GL0 cold-restart and validator-download qualification.
#
# This is an ordinary Just/Docker E2E payload. It exercises the production
# topology: one run-rollback lead starts while every validator is stopped, then
# validators start as run-validator. One validator's real data directory is
# restored to an earlier, previously finalized head so its rejoin must traverse
# the ordinary full-download path.
#
# Usage: bash docker/bin/test-rollback-download-head.sh [gl0_port_prefix]

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
GL0_PORT_PREFIX=${1:-90}
TEST_HOST=${TEST_HOST:-http://localhost}
EXPECTED_GL0_NODES=3
LEAD_INDEX=0
WARM_INDEX=1
TARGET_INDEX=2
LEAD_NODE="gl0-${LEAD_INDEX}"
WARM_NODE="gl0-${WARM_INDEX}"
TARGET_NODE="gl0-${TARGET_INDEX}"
SETUP_TIMEOUT=${ROLLBACK_DOWNLOAD_SETUP_TIMEOUT_SECONDS:-900}
LEAD_TIMEOUT=${ROLLBACK_DOWNLOAD_LEAD_TIMEOUT_SECONDS:-300}
DOWNLOAD_TIMEOUT=${ROLLBACK_DOWNLOAD_VALIDATOR_TIMEOUT_SECONDS:-900}
CONTINUATION_TIMEOUT=${ROLLBACK_DOWNLOAD_CONTINUATION_TIMEOUT_SECONDS:-600}
MIN_BASELINE_ORDINAL=${ROLLBACK_DOWNLOAD_MIN_BASELINE_ORDINAL:-8}
LAG_DISTANCE=${ROLLBACK_DOWNLOAD_LAG_DISTANCE:-4}
CONTINUATION_DISTANCE=${ROLLBACK_DOWNLOAD_CONTINUATION_DISTANCE:-3}

RUN_ID=$(date -u +%Y%m%dT%H%M%SZ)
EVIDENCE_ROOT=${ROLLBACK_DOWNLOAD_EVIDENCE_ROOT:-${TMPDIR:-/tmp}/tessellation-e2e-evidence}
EVIDENCE_DIR="${EVIDENCE_ROOT}/rollback-download-head-${RUN_ID}"
ENV_BACKUP_DIR="${EVIDENCE_DIR}/env-backup"
TARGET_DATA_ARCHIVE="${EVIDENCE_DIR}/target-lagged-data.tar"
mkdir -p "$ENV_BACKUP_DIR"

env_file() {
  echo "${PROJECT_ROOT}/nodes/$1/.env"
}

node_port() {
  local index=${1##gl0-}
  echo $((GL0_PORT_PREFIX * 100 + index * 10))
}

node_url() {
  echo "${TEST_HOST}:$(node_port "$1")"
}

node_p2p_url() {
  echo "${TEST_HOST}:$(( $(node_port "$1") + 1 ))"
}

peer_id_of() {
  tr -d '[:space:]' < "${PROJECT_ROOT}/nodes/$1/peer_id" 2>/dev/null || true
}

get_node_state() {
  curl -fsS --max-time 5 "$(node_url "$1")/node/info" 2>/dev/null |
    jq -r '.state // empty' 2>/dev/null || true
}

get_node_version() {
  curl -fsS --max-time 5 "$(node_url "$1")/node/info" 2>/dev/null |
    jq -r '.version // empty' 2>/dev/null || true
}

get_ordinal() {
  curl -fsS --max-time 5 "$(node_url "$1")/global-snapshots/latest" 2>/dev/null |
    jq -r '.value.ordinal // empty' 2>/dev/null || true
}

get_metadata() {
  curl -fsS --max-time 5 "$(node_url "$1")/global-snapshots/latest/metadata" 2>/dev/null || true
}

get_snapshot_hash() {
  local node=$1
  local ordinal=$2
  curl -fsS --max-time 10 "$(node_url "$node")/global-snapshots/${ordinal}/hash" 2>/dev/null |
    jq -er 'if type == "string" then . else .value // .hash // empty end' 2>/dev/null || true
}

get_snapshot() {
  local node=$1
  local ordinal=$2
  curl -fsS --max-time 15 "$(node_url "$node")/global-snapshots/${ordinal}" 2>/dev/null || true
}

set_env_key() {
  local file=$1
  local key=$2
  local value=$3
  local temporary
  temporary=$(mktemp "${file}.XXXXXX")
  awk -F= -v key="$key" '$1 != key { print }' "$file" > "$temporary"
  printf '%s=%s\n' "$key" "$value" >> "$temporary"
  mv "$temporary" "$file"
}

remove_env_key() {
  local file=$1
  local key=$2
  local temporary
  temporary=$(mktemp "${file}.XXXXXX")
  awk -F= -v key="$key" '$1 != key { print }' "$file" > "$temporary"
  mv "$temporary" "$file"
}

compose_up() {
  local index=$1
  (
    cd "${PROJECT_ROOT}/nodes/${index}"
    docker compose \
      -f docker-compose.test.yaml \
      -f docker-compose.yaml \
      -f docker-compose.volumes.yaml \
      --profile l0 \
      up -d --force-recreate gl0
  )
}

capture_evidence() {
  local phase=${1:-final}
  mkdir -p "${EVIDENCE_DIR}/${phase}"
  docker ps -a --format '{{.Names}}\t{{.Status}}\t{{.Image}}' > "${EVIDENCE_DIR}/${phase}/containers.txt" 2>&1 || true
  for index in $(seq 0 $((EXPECTED_GL0_NODES - 1))); do
    docker logs "gl0-${index}" > "${EVIDENCE_DIR}/${phase}/gl0-${index}.log" 2>&1 || true
    curl -fsS --max-time 5 "$(node_url "gl0-${index}")/node/info" \
      > "${EVIDENCE_DIR}/${phase}/gl0-${index}-node-info.json" 2>/dev/null || true
    curl -fsS --max-time 5 "$(node_url "gl0-${index}")/global-snapshots/latest/metadata" \
      > "${EVIDENCE_DIR}/${phase}/gl0-${index}-metadata.json" 2>/dev/null || true
  done
}

restore_env_files() {
  local index
  for index in $(seq 0 $((EXPECTED_GL0_NODES - 1))); do
    if [ -f "${ENV_BACKUP_DIR}/gl0-${index}.env" ]; then
      cp "${ENV_BACKUP_DIR}/gl0-${index}.env" "$(env_file "$index")"
    fi
  done
}

cleanup() {
  local status=$?
  capture_evidence final
  restore_env_files
  echo "E2E evidence preserved at ${EVIDENCE_DIR}"
  exit "$status"
}
trap cleanup EXIT

fail() {
  local message=$1
  echo "FAIL: $message" >&2
  capture_evidence failure
  for index in $(seq 0 $((EXPECTED_GL0_NODES - 1))); do
    local node="gl0-${index}"
    echo "  $node state=$(get_node_state "$node") ordinal=$(get_ordinal "$node")" >&2
  done
  exit 1
}

wait_log() {
  local node=$1
  local since=$2
  local pattern=$3
  local timeout=$4
  local deadline=$(( $(date +%s) + timeout ))
  local logs

  while [ "$(date +%s)" -lt "$deadline" ]; do
    logs=$(docker logs --since "$since" "$node" 2>&1 || true)
    if grep -Fq "$pattern" <<<"$logs"; then
      return 0
    fi
    sleep 3
  done
  return 1
}

wait_registration_endpoint() {
  local node=$1
  local timeout=$2
  local deadline=$(( $(date +%s) + timeout ))
  local status

  while [ "$(date +%s)" -lt "$deadline" ]; do
    status=$(curl -sS --max-time 3 -o /dev/null -w '%{http_code}' \
      "$(node_p2p_url "$node")/registration/request" 2>/dev/null || true)
    if [ "$status" = "200" ]; then
      return 0
    fi
    sleep 3
  done
  return 1
}

snapshot_has_all_test_proofs() {
  local node=$1
  local ordinal=$2
  local snapshot peer_id index
  snapshot=$(get_snapshot "$node" "$ordinal")
  [ -n "$snapshot" ] || return 1
  for index in $(seq 0 $((EXPECTED_GL0_NODES - 1))); do
    peer_id=$(peer_id_of "$index")
    [ -n "$peer_id" ] || return 1
    jq -e --arg peer "$peer_id" 'any(.proofs[]?; .id == $peer)' <<<"$snapshot" >/dev/null || return 1
  done
}

# Print `ordinal|hash` once all three nodes are Ready, serve one exact hash at
# that ordinal, and the artifact carries a proof from each test operator.
wait_all_ready_common() {
  local minimum=$1
  local timeout=$2
  local require_all_proofs=${3:-true}
  local deadline=$(( $(date +%s) + timeout ))
  local index node state ordinal minimum_seen hash reference all_match status

  while [ "$(date +%s)" -lt "$deadline" ]; do
    minimum_seen=""
    all_match=true
    status=""
    for index in $(seq 0 $((EXPECTED_GL0_NODES - 1))); do
      node="gl0-${index}"
      state=$(get_node_state "$node")
      ordinal=$(get_ordinal "$node")
      status="${status} ${node}:${state:-?}/${ordinal:-?}"
      if [ "$state" != "Ready" ] || [ -z "$ordinal" ]; then
        all_match=false
        break
      fi
      if [ -z "$minimum_seen" ] || [ "$ordinal" -lt "$minimum_seen" ]; then
        minimum_seen=$ordinal
      fi
    done

    if [ "$all_match" = "true" ] && [ -n "$minimum_seen" ] && [ "$minimum_seen" -ge "$minimum" ]; then
      reference=""
      for index in $(seq 0 $((EXPECTED_GL0_NODES - 1))); do
        hash=$(get_snapshot_hash "gl0-${index}" "$minimum_seen")
        if [ -z "$hash" ]; then
          all_match=false
          break
        fi
        if [ -z "$reference" ]; then
          reference=$hash
        elif [ "$hash" != "$reference" ]; then
          all_match=false
          break
        fi
      done
      if [ "$all_match" = "true" ] \
        && { [ "$require_all_proofs" != "true" ] || snapshot_has_all_test_proofs "$LEAD_NODE" "$minimum_seen"; }; then
        printf '%s|%s\n' "$minimum_seen" "$reference"
        return 0
      fi
    fi

    echo "  Waiting for three-operator common proof (minimum=$minimum):${status}" >&2
    sleep 5
  done
  return 1
}

wait_two_node_advance() {
  local minimum=$1
  local timeout=$2
  local deadline=$(( $(date +%s) + timeout ))
  local left right hash_left hash_right candidate

  while [ "$(date +%s)" -lt "$deadline" ]; do
    left=$(get_ordinal "$LEAD_NODE")
    right=$(get_ordinal "$WARM_NODE")
    if [ -n "$left" ] && [ -n "$right" ]; then
      candidate=$(( left < right ? left : right ))
      if [ "$candidate" -ge "$minimum" ]; then
        hash_left=$(get_snapshot_hash "$LEAD_NODE" "$candidate")
        hash_right=$(get_snapshot_hash "$WARM_NODE" "$candidate")
        if [ -n "$hash_left" ] && [ "$hash_left" = "$hash_right" ]; then
          printf '%s|%s\n' "$candidate" "$hash_left"
          return 0
        fi
      fi
    fi
    echo "  Waiting for $LEAD_NODE/$WARM_NODE to reach common ordinal $minimum (left=${left:-?}, right=${right:-?})..." >&2
    sleep 5
  done
  return 1
}

wait_target_rejoin() {
  local minimum=$1
  local since=$2
  local timeout=$3
  local deadline=$(( $(date +%s) + timeout ))
  local state ordinal metadata metadata_ordinal metadata_hash artifact_hash logs metric

  while [ "$(date +%s)" -lt "$deadline" ]; do
    state=$(get_node_state "$TARGET_NODE")
    ordinal=$(get_ordinal "$TARGET_NODE")
    logs=$(docker logs --since "$since" "$TARGET_NODE" 2>&1 || true)
    metric=$(curl -fsS --max-time 5 "$(node_url "$TARGET_NODE")/metrics" 2>/dev/null |
      awk '/^dag_download_head_publication_total\{.*path="full"/ { total += $NF } END { print total + 0 }' || true)

    if [ "$state" = "Ready" ] && [ -n "$ordinal" ] && [ "$ordinal" -ge "$minimum" ] \
      && grep -Fq 'event=DOWNLOAD_INIT_START' <<<"$logs" \
      && { [ "${metric:-0}" -ge 1 ] || grep -Fq '[SnapshotStorage] Publishing validated download/recovery head' <<<"$logs"; }; then
      metadata=$(get_metadata "$TARGET_NODE")
      metadata_ordinal=$(jq -r '.ordinal // empty' <<<"$metadata" 2>/dev/null || true)
      metadata_hash=$(jq -r '.hash // empty' <<<"$metadata" 2>/dev/null || true)
      if [ -n "$metadata_ordinal" ] && [ -n "$metadata_hash" ]; then
        artifact_hash=$(get_snapshot_hash "$TARGET_NODE" "$metadata_ordinal")
        if [ "$artifact_hash" = "$metadata_hash" ]; then
          printf '%s|%s\n' "$metadata_ordinal" "$metadata_hash"
          return 0
        fi
      fi
    fi

    echo "  Waiting for lagged validator full download: state=${state:-?} ordinal=${ordinal:-?} publication=${metric:-0}" >&2
    sleep 5
  done
  return 1
}

wait_consensus_completion_after() {
  local node=$1
  local since=$2
  local ordinal=$3
  local timeout=$4
  local deadline=$(( $(date +%s) + timeout ))
  local logs

  while [ "$(date +%s)" -lt "$deadline" ]; do
    logs=$(docker logs --since "$since" "$node" 2>&1 || true)
    if grep -F 'event=CONSENSUS_FINISHED' <<<"$logs" |
      sed -n \
        -e 's/.*round=SnapshotOrdinal(\([0-9][0-9]*\)).*/\1/p' \
        -e 's/.*round=SnapshotOrdinal{value=\([0-9][0-9]*\)}.*/\1/p' |
      awk -v minimum="$ordinal" '$1 > minimum { found=1 } END { exit !found }'; then
      return 0
    fi
    sleep 5
  done
  return 1
}

archive_target_data() {
  local image
  image=$(docker inspect -f '{{.Config.Image}}' "$TARGET_NODE")
  [ -n "$image" ] || fail "could not resolve assembled image for $TARGET_NODE"
  docker run --rm \
    --volumes-from "$TARGET_NODE" \
    -v "${EVIDENCE_DIR}:/evidence" \
    --entrypoint /bin/bash \
    "$image" -c 'set -e; tar -C /tessellation/data -cpf /evidence/target-lagged-data.tar .'
  [ -s "$TARGET_DATA_ARCHIVE" ] || fail "lagged target archive was not created"
}

restore_target_data() {
  local image
  image=$(docker inspect -f '{{.Config.Image}}' "$TARGET_NODE")
  [ -n "$image" ] || fail "could not resolve assembled image for $TARGET_NODE"
  docker run --rm \
    --volumes-from "$TARGET_NODE" \
    -v "${EVIDENCE_DIR}:/evidence:ro" \
    --entrypoint /bin/bash \
    "$image" -c '
      set -e
      test -s /evidence/target-lagged-data.tar
      find /tessellation/data -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
      tar -C /tessellation/data -xpf /evidence/target-lagged-data.tar
    '
}

echo "================================================"
echo "Rollback-lead / full-download head qualification"
echo "================================================"
echo "  lead:       $LEAD_NODE (run-rollback)"
echo "  warm peer:  $WARM_NODE (run-validator)"
echo "  lagged peer:$TARGET_NODE (run-validator)"
echo "  evidence:   $EVIDENCE_DIR"

running_gl0=$(docker ps --format '{{.Names}}' | awk '/^gl0-[0-9]+$/ { count++ } END { print count+0 }')
[ "$running_gl0" -eq "$EXPECTED_GL0_NODES" ] ||
  fail "requires exactly $EXPECTED_GL0_NODES running GL0 nodes; found $running_gl0"

for index in $(seq 0 $((EXPECTED_GL0_NODES - 1))); do
  [ -f "$(env_file "$index")" ] || fail "missing generated environment for gl0-${index}"
  cp "$(env_file "$index")" "${ENV_BACKUP_DIR}/gl0-${index}.env"
done

{
  echo "run_id=$RUN_ID"
  echo "git_head=$(git -C "$PROJECT_ROOT" rev-parse HEAD)"
  echo "gl0_jar_sha256=$(sha256sum "${PROJECT_ROOT}/docker/jars/gl0.jar" | awk '{print $1}')"
  for index in $(seq 0 $((EXPECTED_GL0_NODES - 1))); do
    echo "gl0_${index}_peer_id=$(peer_id_of "$index")"
    echo "gl0_${index}_version=$(get_node_version "gl0-${index}")"
  done
} > "${EVIDENCE_DIR}/run-metadata.txt"

echo "Phase 1: preserving a genuine lagged validator head..."
baseline=$(wait_all_ready_common "$MIN_BASELINE_ORDINAL" "$SETUP_TIMEOUT") ||
  fail "could not establish an all-Ready, all-signed baseline"
IFS='|' read -r lagged_ordinal lagged_hash <<<"$baseline"
echo "  Lagged checkpoint: ordinal=$lagged_ordinal hash=${lagged_hash:0:16}..."

docker stop "$TARGET_NODE" >/dev/null
archive_target_data
docker start "$TARGET_NODE" >/dev/null

anchor_minimum=$((lagged_ordinal + LAG_DISTANCE))
anchor=$(wait_all_ready_common "$anchor_minimum" "$SETUP_TIMEOUT") ||
  fail "cluster did not produce a later all-signed rollback anchor"
IFS='|' read -r rollback_ordinal rollback_hash <<<"$anchor"
echo "  Rollback anchor: ordinal=$rollback_ordinal hash=${rollback_hash:0:16}..."
capture_evidence before-cold-restart

echo "Phase 2: stopping the full fleet and restoring $TARGET_NODE to ordinal $lagged_ordinal..."
docker stop "$LEAD_NODE" "$WARM_NODE" "$TARGET_NODE" >/dev/null
restore_target_data

set_env_key "$(env_file "$LEAD_INDEX")" CL_DOCKER_GL0_GENESIS true
set_env_key "$(env_file "$LEAD_INDEX")" CL_DOCKER_GL0_JOIN false
set_env_key "$(env_file "$LEAD_INDEX")" CL_DOCKER_ROLLBACK true
set_env_key "$(env_file "$LEAD_INDEX")" CL_DOCKER_ROLLBACK_HASH "$rollback_hash"

for index in "$WARM_INDEX" "$TARGET_INDEX"; do
  set_env_key "$(env_file "$index")" CL_DOCKER_GL0_GENESIS false
  set_env_key "$(env_file "$index")" CL_DOCKER_GL0_JOIN true
  remove_env_key "$(env_file "$index")" CL_DOCKER_ROLLBACK
  remove_env_key "$(env_file "$index")" CL_DOCKER_ROLLBACK_HASH
done

echo "Phase 3: starting only the rollback lead..."
lead_since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
compose_up "$LEAD_INDEX"
wait_registration_endpoint "$LEAD_NODE" "$LEAD_TIMEOUT" ||
  fail "rollback lead never exposed its registration endpoint"
wait_log "$LEAD_NODE" "$lead_since" 'Successfully initialized lastNGlobalSnapshot shared storage' "$LEAD_TIMEOUT" ||
  fail "rollback lead did not reconstruct Last-N while validators were stopped"

running_peers=$(docker ps --format '{{.Names}}' | awk -v lead="$LEAD_NODE" '/^gl0-[0-9]+$/ && $0 != lead { count++ } END { print count+0 }')
[ "$running_peers" -eq 0 ] || fail "a validator was running during rollback-lead-only initialization"
lead_logs=$(docker logs --since "$lead_since" "$LEAD_NODE" 2>&1 || true)
grep -Fq "run-rollback $rollback_hash" <<<"$lead_logs" ||
  fail "lead did not start with the selected rollback hash"
capture_evidence lead-only

echo "Phase 4: starting the warm validator, then the lagged validator..."
compose_up "$WARM_INDEX"
warm_progress=$(wait_two_node_advance "$((rollback_ordinal + 1))" "$SETUP_TIMEOUT") ||
  fail "rollback lead and warm validator did not align on the selected anchor"
IFS='|' read -r warm_ordinal warm_hash <<<"$warm_progress"
echo "  Lead/warm aligned: ordinal=$warm_ordinal hash=${warm_hash:0:16}..."

target_since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
compose_up "$TARGET_INDEX"
downloaded=$(wait_target_rejoin "$rollback_ordinal" "$target_since" "$DOWNLOAD_TIMEOUT") ||
  fail "$TARGET_NODE did not complete ordinary full download and publish its exact head"
IFS='|' read -r downloaded_ordinal downloaded_hash <<<"$downloaded"
echo "  Downloaded public head: ordinal=$downloaded_ordinal hash=${downloaded_hash:0:16}..."

wait_consensus_completion_after "$TARGET_NODE" "$target_since" "$downloaded_ordinal" "$DOWNLOAD_TIMEOUT" ||
  fail "$TARGET_NODE became Ready but did not complete a later consensus round"

echo "Phase 5: checking one exact post-join hash and continued production..."
common=$(wait_all_ready_common "$downloaded_ordinal" "$DOWNLOAD_TIMEOUT" false) ||
  fail "all three nodes did not converge to one post-download artifact"
IFS='|' read -r common_ordinal common_hash <<<"$common"
continuation_target=$((common_ordinal + CONTINUATION_DISTANCE))
continued=$(wait_all_ready_common "$continuation_target" "$CONTINUATION_TIMEOUT" false) ||
  fail "cluster did not continue for $CONTINUATION_DISTANCE ordinals after validator rejoin"
IFS='|' read -r final_ordinal final_hash <<<"$continued"

{
  echo "lagged_ordinal=$lagged_ordinal"
  echo "lagged_hash=$lagged_hash"
  echo "rollback_ordinal=$rollback_ordinal"
  echo "rollback_hash=$rollback_hash"
  echo "downloaded_ordinal=$downloaded_ordinal"
  echo "downloaded_hash=$downloaded_hash"
  echo "common_ordinal=$common_ordinal"
  echo "common_hash=$common_hash"
  echo "final_ordinal=$final_ordinal"
  echo "final_hash=$final_hash"
  docker logs --since "$lead_since" "$LEAD_NODE" 2>&1 |
    grep -m1 -E 'deterministicConfigHash|deterministic config hash' || true
} >> "${EVIDENCE_DIR}/run-metadata.txt"

capture_evidence success
echo
echo "================================================"
echo "PASS: rollback lead and downloaded-head lifecycle"
echo "================================================"
echo "Lead initialized Last-N alone at $rollback_ordinal; $TARGET_NODE downloaded from retained $lagged_ordinal,"
echo "published exact head $downloaded_ordinal, became Ready, completed consensus, matched all peers at"
echo "$common_ordinal/$common_hash, and the cluster continued through $final_ordinal."
