#!/usr/bin/env bash
#
# ACTIONS_RUNNER_HOOK_JOB_STARTED — runs before each job on a fixed (persistent)
# runner.
#
# Defensive: job-completed.sh does the real teardown, but a hard failure (runner
# restart, OOM kill, force-cancelled job) can skip it. Starting from known-clean
# state prevents one bad job from poisoning every subsequent one — the classic
# "only fails on ci-runner-2" failure mode.
#
# Never fails the job: a cleanup problem should show up in the log, not as a red
# X on unrelated code.
set -uo pipefail

echo "::group::Runner pre-flight cleanup"

echo "--- host state ---"
nproc 2>/dev/null | sed 's/^/cores: /'
free -m 2>/dev/null | head -2
df -h / 2>/dev/null | tail -1

# Stale containers from a previous job that never completed its hook.
STALE="$(docker ps -aq 2>/dev/null)"
if [ -n "$STALE" ]; then
  echo "--- removing $(echo "$STALE" | wc -l | tr -d ' ') stale container(s) ---"
  docker rm -f $STALE >/dev/null 2>&1 || true
fi

# The harness creates this network with a fixed name and subnet and fails if a
# stale one exists (docker/bin/compose-runner.sh:174).
if docker network inspect tessellation_common >/dev/null 2>&1; then
  echo "--- removing stale tessellation_common network ---"
  docker network rm tessellation_common >/dev/null 2>&1 || true
fi

# Root-owned node data left in the workspace by a previous run's containers.
# actions/checkout cannot delete these as the `runner` user, so the job would
# fail before it started. Deleted from inside a container, which runs as root —
# same trick as the `clean-data` recipe in the justfile.
if [ -n "${GITHUB_WORKSPACE:-}" ] && [ -d "${GITHUB_WORKSPACE}/nodes" ]; then
  echo "--- removing root-owned nodes/ from the workspace ---"
  docker run --rm -v "${GITHUB_WORKSPACE}/nodes:/nodes" alpine \
    sh -c 'rm -rf /nodes/* 2>/dev/null || true' >/dev/null 2>&1 || true
  rm -rf "${GITHUB_WORKSPACE}/nodes" 2>/dev/null || true
fi

# Disk guard. A full disk manifests as bizarre mid-test failures (docker build
# errors, JVMs unable to write logs), so reclaim aggressively while it is cheap.
USED=$(df --output=pcent / 2>/dev/null | tail -1 | tr -dc '0-9')
if [ -n "$USED" ] && [ "$USED" -gt 70 ]; then
  echo "--- disk at ${USED}%, pruning ---"
  docker system prune -af --volumes >/dev/null 2>&1 || true
  df -h / | tail -1
fi

echo "::endgroup::"
exit 0
