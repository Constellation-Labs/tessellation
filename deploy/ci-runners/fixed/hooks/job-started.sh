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

# Artifacts the workflow installs into SHARED locations must not survive between
# jobs. The workflow's install steps assume a pristine hosted runner and are not
# re-runnable — each of these fails, rather than no-ops, if the target exists:
#
#   /usr/local/bin/just
#     "Install just" pipes the upstream installer, which ABORTS with
#     "error: `/usr/local/bin/just` already exists" instead of overwriting.
#
#   /usr/share/keyrings/sbt-archive-keyring.gpg
#     "Install sbt" runs `sudo gpg --dearmor -o <that path>`. gpg will not
#     silently overwrite: it tries to prompt, finds no tty under the runner, and
#     dies with "gpg: cannot open '/dev/tty'" (exit 2).
#
# Both are invisible on GitHub's ephemeral runners and break every job after the
# first on a persistent one. Removing them restores the fresh-runner baseline.
#
# NOT removed: the sbt apt package itself and the setup-java/setup-node
# hostedtoolcache entries. Those re-install idempotently and dropping them would
# add minutes to every job.
echo "--- clearing non-idempotent workflow install artifacts ---"
sudo rm -f /usr/local/bin/just
sudo rm -f /usr/share/keyrings/sbt-archive-keyring.gpg
sudo rm -f /etc/apt/sources.list.d/sbt.list

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
