---
description: "Diagnose an E2E fork-recovery failure from persisted per-node logs using parallel subagents"
---

# Debug E2E Logs

Use this slash command when the user reports an E2E test failure — fork-recovery, consensus stall, committee divergence, pre-isolation sync timeout, etc. It codifies the procedure documented in `reference_e2e_log_analysis.md`. Do not jump to fixes or blame a recent commit without running this procedure first.

## Inputs

- **$ARGUMENTS** (optional): free-text notes from the user about the failure (which node stuck, which ordinal, what test output showed, which commit to investigate). If empty, use the most recent prior user message as context.

## STEP 1 — Confirm logs exist

```bash
ls -la /home/scas/git/tessellation/nodes/0/gl0-logs/
wc -l /home/scas/git/tessellation/nodes/*/gl0-logs/gl0-run.log
```

If logs aren't present (no `nodes/<N>/gl0-logs/gl0-run.log`), tell the user the E2E teardown wiped them and propose adding `docker logs gl0-N > ... .log` capture to `docker/bin/test-fork-recovery.sh`. Stop.

If logs are present, continue.

## STEP 2 — Build the node-id ↔ peer-id mapping for this run

Peer IDs are keypair-derived and **rotate every compose cold-start** — you cannot trust a prior mapping. Extract the current mapping:

```bash
for n in 0 1 2 3 4; do
  echo -n "gl0-$n: "
  cat /home/scas/git/tessellation/nodes/$n/peer_id 2>/dev/null | head -c 8
  echo
done
```

Emit the table to the user so they see the IDs for this specific run.

## STEP 3 — Identify the affected nodes

From the user's notes + terminal output, pick the 1-3 nodes where the failure visibly manifested. Typical signals:
- `Waiting… gl0-X:ord=<low>` while others advanced — **lag**
- `fac=<smaller>` on one node while others show `fac=<larger>` — **divergent committee view**
- `ord=?` — **unreachable**

## STEP 4 — Dispatch subagents in parallel

One subagent per affected node, all in a **single message** with multiple Agent tool calls so they run concurrently.

Each agent prompt must include:

1. **Context**: cluster size (typically 5 gl0 nodes), quorum fraction (typically 0.67), isolation target, monitor, start time, expected deadline.
2. **Observed failure**: paste the relevant terminal output. Include the peer-id ↔ node-id mapping from STEP 2 — subagents also fall into the "which peer-id is which node" trap without it.
3. **Recent changes under investigation**: the last N commit SHAs + 2-3 line summary each. Ask explicitly: "is this failure plausibly caused by those changes, or a pre-existing pattern?"
4. **Numbered diagnostic checklist** — typically:
   - Round-by-round timeline: join, leader rotation, lag onset, abandonments, recoveries
   - Did B1 `EVICTION assembly=quorum_reached_cert_stored` fire? How many targets?
   - Did B2 `ADMISSION` events fire (received votes, cert-stored, applied)?
   - Any new error codes from recent commits (`acs_last_snap_mismatch`, `ecs_target_not_in_committee`, `acs_build_failed`, `ecs_*`, etc.)?
   - Did `RECOVERY_DOWNLOAD_TRIGGERED` fire? How many times? Did `Caught-up shortcut` log line appear?
   - Divergence point — first round where this node's view of committee/tip differs from peers
   - Is this behavior visible in the log identical to the pre-change flaky pattern, or novel?
5. **Output format**: under ~400 words, with timestamps + quoted log lines for key events, and a clear verdict on "regression vs pre-existing".

The log file path for each: `/home/scas/git/tessellation/nodes/<N>/gl0-logs/gl0-run.log` (world-readable, no sudo). Warn the agent these are 20-30k lines — to read fully, not grep.

## STEP 5 — Cross-correlate and write verdict

When all agents return:

- Line up key timestamps across reports; confirm causality direction
- Check whether the verdicts agree on regression-vs-pre-existing
- Quote 2-4 decisive log lines in the user-facing summary
- If verdict is **pre-existing pattern**: call it out clearly, propose a rerun or a targeted fix for the pattern itself (not the innocent commit)
- If verdict is **regression**: name the commit, the specific change, and the proposed fix — include a test plan

## STEP 6 — Log what was found

Update `.workspace/fork-recovery-test-runs.md` (create if missing) with a dated entry:

```
## <YYYY-MM-DD HH:MM> — <commit-sha>
- Result: PASS / FAIL (phase: …)
- Root cause: …
- Verdict: regression / pre-existing / flake
- Fix hypothesis: …
- What was ruled out: …
```

This is the institutional memory that prevents the same failure being diagnosed from scratch next time.

## Do NOT do any of these

- Grep the logs and guess from patterns alone. Always a full read.
- Blame the most recent commit without log evidence.
- Serialize the subagents — always parallel.
- Skip STEP 2 and reuse a prior run's peer-id mapping.
- Propose a fix before the agents return.
- Write tests or modify code before this procedure completes.
