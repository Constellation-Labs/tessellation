#!/usr/bin/env bash
# Test-only preflight: readiness is not Docker /node/health or committee membership.
set -euo pipefail

ready_timeout=${CL_DOCKER_JOIN_READY_TIMEOUT_SECONDS:-120}
if [[ ! "$ready_timeout" =~ ^[1-9][0-9]*$ ]]; then
  echo "ERROR: CL_DOCKER_JOIN_READY_TIMEOUT_SECONDS must be a positive integer" >&2
  exit 1
fi

deadline=$((SECONDS + ready_timeout))
local_url="http://127.0.0.1:${CL_PUBLIC_HTTP_PORT:?}"
cli_url="http://127.0.0.1:${CL_DOCKER_JOIN_CLI_PORT:?}"
seed_url="http://${CL_DOCKER_JOIN_IP:?}:${CL_DOCKER_JOIN_PORT:?}"
seed_id=${CL_DOCKER_JOIN_ID:?}
waiting_for=not_observed

# Bound each request by both a short probe timeout and the remaining overall budget.
probe() {
  local remaining=$((deadline - SECONDS))
  ((remaining > 0)) || return 124
  curl --silent --connect-timeout 1 --max-time "$((remaining < 2 ? remaining : 2))" "$@"
}

# Remember the last condition that actually failed, not a successful prerequisite
# revisited just as the overall deadline expires. 124 means no probe was attempted.
check() {
  local stage=$1 filter=$2 body status=0
  shift 2
  body=$(probe "$@") || status=$?
  ((status != 124)) || return 1
  if ((status != 0)) || { [ -n "$filter" ] && ! jq -e --arg id "$seed_id" "$filter" <<< "$body" >/dev/null 2>&1; }; then
    waiting_for=$stage
    return 1
  fi
}

echo "Waiting for E2E join readiness: node=${CL_DOCKER_ID:-unknown} seed=$seed_url timeout=${ready_timeout}s"
while ((SECONDS < deadline)); do
  # GET / may return 404: it proves only that the loopback CLI is listening.
  # The existing unauthenticated registration route must have an active session.
  # Match NodeState.inCluster, not Ready: waiting for a fully formed committee
  # here could deadlock bootstrap. The JVM still performs the actual handshake.
  if check local_state '. == "ReadyToJoin"' --fail "$local_url/node/state" &&
    check local_cli '' --output /dev/null "$cli_url/" &&
    check seed_registration '
      .id == $id and .session != null and .clusterSession != null and
      (.state as $state | ["WaitingForObserving", "Observing", "WaitingForReady",
        "Ready", "WaitingForDownload", "DownloadInProgress"] | index($state) != null)
    ' --fail "$seed_url/registration/request"; then
    echo "E2E join ready: node=${CL_DOCKER_ID:-unknown} seed=$seed_url"
    exit 0
  fi
  ((SECONDS < deadline)) && sleep 1
done

echo "ERROR: E2E join readiness timed out after ${ready_timeout}s: node=${CL_DOCKER_ID:-unknown} waiting_for=$waiting_for local=$local_url cli=$cli_url seed=$seed_url" >&2
exit 1
