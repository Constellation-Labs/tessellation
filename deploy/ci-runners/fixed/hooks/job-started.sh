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
# Everything destructive is scoped to the harness's own containers, network and
# volumes — see lib-harness.sh. The ONE exception is the install-artifact removal
# below, which is host-global by necessity; it is the only part of this hook that
# reaches outside the harness's own state.
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
# HOST-GLOBAL: unlike everything else in this hook these paths are not scoped to
# the harness. On a box shared with other tooling, anything else depending on
# /usr/local/bin/just or the sbt apt source will lose it here.
#
# NOT removed: the sbt apt package itself and the setup-java/setup-node
# hostedtoolcache entries. Those re-install idempotently and dropping them would
# add minutes to every job.
echo "--- clearing non-idempotent workflow install artifacts ---"
sudo rm -f /usr/local/bin/just
sudo rm -f /usr/share/keyrings/sbt-archive-keyring.gpg
sudo rm -f /etc/apt/sources.list.d/sbt.list

# Fail SAFE, not open: if the library is missing, leak state rather than fall
# back to host-wide removal. Re-run register-runners.sh to reinstall it.
# shellcheck source=lib-harness.sh
if ! . "$(dirname "${BASH_SOURCE[0]}")/lib-harness.sh" 2>/dev/null; then
  echo "::warning::lib-harness.sh not found next to this hook — skipping cleanup." \
       "Re-run deploy/ci-runners/fixed/register-runners.sh to reinstall the hooks."
  echo "::endgroup::"
  exit 0
fi

# Stale harness state from a previous job that never completed its hook.
remove_harness_state
clean_workspace_nodes

# Lower threshold than job-completed: reclaim while it is still cheap. A full
# disk manifests as bizarre mid-test failures (docker build errors, JVMs unable
# to write logs), not as clean "no space" messages.
reclaim_disk 70

echo "::endgroup::"
exit 0
