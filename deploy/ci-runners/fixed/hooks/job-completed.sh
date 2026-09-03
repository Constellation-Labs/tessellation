#!/usr/bin/env bash
#
# ACTIONS_RUNNER_HOOK_JOB_COMPLETED — runs after each job on a fixed (persistent)
# runner.
#
# Tears down the E2E cluster and reclaims disk. Runs AFTER the workflow's own
# "Collect Docker container logs" / "Upload logs" steps, so destroying containers
# here does not cost us diagnostics.
#
# Never fails the job: the job's real result is already decided, and a cleanup
# error must not turn a green run red.
set -uo pipefail

echo "::group::Runner post-job cleanup"

# The E2E topology is up to 13 containers (3 gl0 + 3 gl1 + 1 ml0 + 3 cl1 + 3 dl1)
# plus snapshot-streaming and its postgres. `restart: unless-stopped` in
# docker-compose.yaml means a plain kill would see them come back — rm -f is
# required.
RUNNING="$(docker ps -aq 2>/dev/null)"
if [ -n "$RUNNING" ]; then
  echo "--- removing $(echo "$RUNNING" | wc -l | tr -d ' ') container(s) ---"
  docker rm -f $RUNNING >/dev/null 2>&1 || true
fi

# Fixed name + fixed subnet, so it must be gone before the next job recreates it.
docker network rm tessellation_common >/dev/null 2>&1 || true

# Named volumes (gl0-data-N / gl1-data-N from docker-compose.volumes.yaml).
docker volume prune -f >/dev/null 2>&1 || true

# Root-owned node data/logs written by the containers. Removed from inside a
# container because the `runner` user cannot unlink root-owned files, and leaving
# them breaks the next job's checkout.
if [ -n "${GITHUB_WORKSPACE:-}" ] && [ -d "${GITHUB_WORKSPACE}/nodes" ]; then
  echo "--- removing root-owned nodes/ from the workspace ---"
  docker run --rm -v "${GITHUB_WORKSPACE}/nodes:/nodes" alpine \
    sh -c 'rm -rf /nodes/* 2>/dev/null || true' >/dev/null 2>&1 || true
  rm -rf "${GITHUB_WORKSPACE}/nodes" 2>/dev/null || true
fi

# Keep tagged base images (ubuntu, alpine, the tessellation:test layers) so the
# next job doesn't re-pull or fully rebuild — only dangling layers go. The
# threshold prune below is the escape hatch when that isn't enough.
docker image prune -f >/dev/null 2>&1 || true
docker builder prune -f --keep-storage 10GB >/dev/null 2>&1 || true

USED=$(df --output=pcent / 2>/dev/null | tail -1 | tr -dc '0-9')
echo "--- disk after cleanup: ${USED:-?}% ---"
if [ -n "$USED" ] && [ "$USED" -gt 80 ]; then
  echo "--- above 80%, full prune ---"
  docker system prune -af --volumes >/dev/null 2>&1 || true
  df -h / | tail -1
fi

echo "::endgroup::"
exit 0
