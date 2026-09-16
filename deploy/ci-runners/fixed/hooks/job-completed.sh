#!/usr/bin/env bash
#
# ACTIONS_RUNNER_HOOK_JOB_COMPLETED — runs after each job on a fixed (persistent)
# runner.
#
# Tears down the E2E cluster and reclaims disk. Runs AFTER the workflow's own
# "Collect Docker container logs" / "Upload logs" steps, so destroying containers
# here does not cost us diagnostics.
#
# Everything destructive is scoped to the harness's own containers, network and
# volumes — see lib-harness.sh for how they are identified and why host-wide
# `docker ps -aq` / `prune` was removed.
#
# Never fails the job: the job's real result is already decided, and a cleanup
# error must not turn a green run red.
set -uo pipefail

echo "::group::Runner post-job cleanup"

# Fail SAFE, not open: if the library is missing, leak state rather than fall
# back to host-wide removal. Re-run register-runners.sh to reinstall it.
# shellcheck source=lib-harness.sh
if ! . "$(dirname "${BASH_SOURCE[0]}")/lib-harness.sh" 2>/dev/null; then
  echo "::warning::lib-harness.sh not found next to this hook — skipping cleanup." \
       "Re-run deploy/ci-runners/fixed/register-runners.sh to reinstall the hooks."
  echo "::endgroup::"
  exit 0
fi

remove_harness_state
clean_workspace_nodes
reclaim_disk 80

echo "::endgroup::"
exit 0
