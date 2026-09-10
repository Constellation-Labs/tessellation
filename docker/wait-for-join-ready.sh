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
waiting_for=local_state

# Bound each request by both a short probe timeout and the remaining overall budget.
probe() {
  local remaining=$((deadline - SECONDS))
  ((remaining > 0)) || return 1
  curl --silent --connect-timeout 1 --max-time "$((remaining < 2 ? remaining : 2))" "$@"
}

echo "Waiting for E2E join readiness: node=${CL_DOCKER_ID:-unknown} seed=$seed_url timeout=${ready_timeout}s"
while ((SECONDS < deadline)); do
  waiting_for=local_state
  if state=$(probe --fail "$local_url/node/state") &&
    jq -e '. == "ReadyToJoin"' <<< "$state" >/dev/null 2>&1; then
    waiting_for=local_cli
    # GET / may return 404: that is enough to prove the loopback CLI is listening.
    # /node/state above, not this socket probe, establishes the application state.
    if probe --output /dev/null "$cli_url/"; then
      waiting_for=seed_registration
      # This existing, unauthenticated P2P bootstrap route requires an active session.
      # Match NodeState.inCluster, not Ready: waiting for a fully formed committee
      # here could deadlock bootstrap. The JVM still performs the actual handshake.
      if registration=$(probe --fail "$seed_url/registration/request") &&
        jq -e --arg id "$seed_id" '
          .id == $id and .session != null and .clusterSession != null and
          (.state as $state | ["WaitingForObserving", "Observing", "WaitingForReady",
            "Ready", "WaitingForDownload", "DownloadInProgress"] | index($state) != null)
        ' <<< "$registration" >/dev/null 2>&1; then
        echo "E2E join ready: node=${CL_DOCKER_ID:-unknown} seed=$seed_url"
        exit 0
      fi
    fi
  fi
  ((SECONDS < deadline)) && sleep 1
done

echo "ERROR: E2E join readiness timed out after ${ready_timeout}s: node=${CL_DOCKER_ID:-unknown} waiting_for=$waiting_for local=$local_url cli=$cli_url seed=$seed_url" >&2
exit 1
