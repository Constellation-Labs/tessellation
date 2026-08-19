# Global L0 trusted recovery seed committee

`CL_GL0_RECOVERY_SEED_COMMITTEE` is an emergency, trusted-operator override for a
Global L0 chain whose rollback anchor was finalized by a committee that is no
longer viable. It replaces only the initial operational committee at that real
incremental anchor. It does not replace the signed snapshot, snapshot context,
snapshot hash, state proof, or chain data.

This is the unsigned alternative to the anchor-bound signed recovery plan. Use
one or the other, never both. The environment value is intentionally not part
of `versionHash`, `deterministicConfigHash`, a snapshot, or a consensus message.
Authorization is therefore the operator's control of the source-node launch
environment and the coordinated cold restart, not an on-chain signature.

The feature is inert when the environment variable is absent. It changes no
public schema and needs no ordinal activation. It does change initial consensus
behavior when armed, so every node in the fleet must run the same distinctly
tagged release and effective consensus configuration. The feature first shipped
in rc.9; any later release carrying it must still be deployed fleet-wide under
its own advertised version. Mixed consensus behavior is unsupported.

## Required topology

- Stop the entire fleet before the operation.
- Exactly one controlled source node runs `run-rollback <anchor-hash>`.
- Every other node runs the normal `run-validator` command.
- Set the same `CL_GL0_RECOVERY_SEED_COMMITTEE` value on every named recovery
  member, including the rollback lead.
- Do not set it on community validators or any node absent from the list.
- Do not also configure `--recovery-plan` or `CL_GL0_RECOVERY_PLAN_PATH`.

The expected IntegrationNet recovery cohort is the three controlled source
nodes. The parser accepts a comma-separated list in any order and canonicalizes
it as a `SortedSet[PeerId]`; every entry must be a unique 128-character lowercase
hex PeerId. Startup rejects an empty/malformed list, a singleton, a local node
that is absent from the list, a member outside the seedlist or configured
allowance list, a committee over the facilitator-selector cap, or a committee
that cannot prove the next seat under the deployed quorum fraction. Every
member must also satisfy collateral in the exact loaded anchor context.

Example:

```bash
export CL_GL0_RECOVERY_SEED_COMMITTEE='<source-1-peer-id>,<source-2-peer-id>,<source-3-peer-id>'
```

This value is deliberately unsigned. A named validator cannot prove over the
existing consensus-outcome endpoint that another named node was launched with
the variable; it can only prove that all named nodes installed and serve the
same exact synthetic outcome. The trusted operational contract is therefore
load-bearing: independently verify the byte-identical environment on all named
source nodes before starting them. This mechanism is not suitable for an
untrusted or permissionless recovery cohort.

## What the node does

The rollback lead accepts only an incremental-snapshot rollback source and
requires the loaded snapshot hash to equal the `run-rollback` hash exactly. If
`CL_RECOVERY_CHECKPOINT_PATH` is also configured, the independently signed
checkpoint must describe that same network, ordinal, and hash. These source,
hash, checkpoint, public-key, and collateral checks run before rollback storage
is mutated.

The lead then uses the existing typed `GlobalRecoveryPlanOutcome.seed`
constructor. The resulting outcome:

- contains the real signed anchor artifact, context, and ordinal-selected hash;
- sets `facilitators` and `eligibleFacilitators` to the canonical environment
  committee;
- clears removal, withdrawal, penalty, probation, tier, quality, signer, and
  controller-evidence windows that could filter the operator-selected cohort;
  and
- seeds `recentProofSizes` with the selected committee size, matching rc.8's
  canonical committee-size bootstrap semantics.

Named validators download the real anchor normally, independently reconstruct
that exact typed outcome, check collateral, and reject any different value.
The existing generation-bound all-member barrier then holds every named node
until all named peers are in the current cluster session, are `Ready` or
`WaitingForReady`, and serve the exact same outcome. Unrelated Ready peers do
not count. There is no timeout bypass.

Rc.12's ordinary Global L0 first-round synchronizer is complementary and has
strictly lower startup precedence. When this recovery environment is present,
the operator-selected committee continues to require **all** named members on
the current poll; it is not weakened to the normal anchor committee's `Q(N)`
barrier and does not use the normal session alignment cache. See
[Global L0 first-round alignment](global-l0-first-round-alignment.md).

The override is per-JVM-invocation authority. It remains armed through the first
round and disarms in that running process on the first accepted successor
snapshot. It does not continue forcing the selected committee on every later
ordinal. The external environment is intentionally repeatable: every fresh
selected-source JVM launch reads it again and re-arms, until the operator
comments or removes the variable. There is no consumed receipt or tombstone for
the unsigned path.

Disarm and rollout health are deliberately separate. After disarm the node
continues to track whether an accepted snapshot contains enough actual proofs
from the selected committee to prove the next seat:

```text
selected proof signers >= Q(selected committee size + 1)
```

With three source nodes and IntegrationNet's 2/3 quorum, all three source IDs
must appear in one accepted snapshot's proofs before the headroom gauge becomes
ready. Foreign proofs do not count. A first successor with two selected proofs
is valid and disarms the authority, but community release and restart
automation remain blocked until all three prove headroom. This lets a selected
validator that missed the first successor rejoin through the ordinary download
path after its invocation-local authority is cleared, instead of keeping the
synthetic-anchor validator armed indefinitely.

At disarm the running process only clears its process-local override. In-process
leave/fork restart methods never carry the unsigned override, so an unexpected
internal restart cannot silently replay the authority. It restarts that node as
an ordinary validator; the operator must treat the coordinated attempt as
interrupted and reconcile the cohort. The process does not otherwise restart,
the jar does not change, and the application does not modify a service file,
persistent launch command, or filesystem symlink. The rollback lead's normal
persistent role remains `run-rollback` even though its in-JVM recovery restart
method is the ordinary validator path.

Leaving the environment configured is supported and intentionally reapplies the
same trusted committee on later coordinated cold restarts. The rollback lead's
new `run-rollback` invocation must always receive the newly verified canonical
incremental anchor; persisting an old anchor would deliberately replay an old
lineage and can poison snapshot-streaming. Commenting or removing the variable
before a later external start disables this override and restores ordinary
proof-signer rollback seeding. Neither choice changes the normal launch
topology: the controlled rollback lead remains the fleet's one `run-rollback`
node and every other node remains a `run-validator`.

Each re-armed start is a complete synthetic operational reseed, not merely a
preferred-leader hint. It replaces the anchor proof committee with the selected
set and clears the operational tier, penalty, probation, quality, signer, and
controller-evidence windows listed above. Community membership and reward
breadth must therefore regrow from the selected cohort after every such start.

An isolated external restart of one selected source while the variable remains
set also re-arms that one process. It cannot pass the exact all-member recovery
barrier or reseed an active network by itself, and may fail closed against the
already-advanced ordinary outcome. Therefore independent source-node restart
automation must either omit the variable for that restart or escalate to the
documented coordinated source/fleet recovery. Persistent mode is intended for
coordinated external cold starts, not unobserved single-node cycling.

> **DANGER:** The all-member barrier prevents one selected source from acting
> alone; it does not prevent the complete selected cohort from acting together.
> Restarting every selected source with the variable while community nodes are
> still active can release the barrier and create a selected-cohort lineage.
> Never permit unattended selected-cohort or fleet restart automation to reuse
> this variable. A repeat recovery must be explicitly authorized by an operator,
> first stop and verify the full fleet, freshly verify its canonical anchor,
> stage the source cohort as described below, and apply the snapshot-streaming
> precautions.

## Incident runbook

1. Disable all automatic restart/rollback automation. A monitor must not kill a
   named member while the exact all-member barrier is pending.
2. Pause snapshot-streaming before any rollback that can replace already
   indexed ordinals. Determine the last common `(ordinal, hash)` and the first
   replaced ordinal. Snapshot-streaming treats source-node history as final and
   its ordinal uniqueness cannot reconcile an abandoned fork automatically.
3. Stop the full IntegrationNet fleet. Confirm every node will start the same
   immutable release/version and effective `deterministicConfigHash`.
4. Select a canonical **incremental** anchor by exact ordinal, hash, snapshot
   content, state proof, and snapshot info—not by recency alone. If a signed
   recovery checkpoint is configured, confirm it is the same anchor.
5. Select a viable, collateralized recovery cohort. For the normal source-node
   procedure, use all three controlled source PeerIds. Independently compare the
   exact environment value on all three machines.
6. Start exactly one source node with `run-rollback <exact-anchor-hash>` and the
   environment value. Start the other two source nodes with ordinary
   `run-validator` and the same value. Do not start a second rollback node.
7. Verify all three source JVMs report
   `dag_consensus_recovery_seed_armed == 1` and the same canonical committee.
   The barrier must name only the expected source PeerIds.
8. Decide and record the future-start policy without changing a launch-role or
   jar symlink:
   - leave `CL_GL0_RECOVERY_SEED_COMMITTEE` configured on the three selected
     sources to reapply this committee at the newly supplied canonical anchor
     on every coordinated external cold restart; or
   - comment/remove it to make future JVM launches use ordinary rollback
     seeding.
   Changing the persistent environment does not alter authority already parsed
   by the running JVMs. Do not restart merely to apply either policy.
9. Verify all three serve the same anchor and reconstructed outcome, the current
   alignment gauges are zero, and at least one `aligned=true` poll occurred.
   Temporary missing-session or `aligned=false` polls are expected while the
   sources start sequentially. Then wait for the first accepted successor and
   verify the same successor ordinal and hash on all three sources,
   `dag_consensus_recovery_seed_armed == 0` and
   `dag_consensus_recovery_seed_disarmed_total` incremented on every running
   source.
10. Keep community nodes and restart automation held while the source cohort
   establishes next-seat headroom. For a three-node IntegrationNet seed this
   means one accepted snapshot with proofs from all three sources. Inspect that
   canonical accepted proof set directly. On every continuously running
   recovery-origin process, also verify
   `dag_consensus_recovery_seed_headroom_ready == 1` and headroom deficit `== 0`.
   An in-process straggler restart without the override sees the prior recovery
   resource reset its invocation-scoped gauges to zero. A fresh process that
   never had the override may expose no recovery-seed gauges. Only continuously
   running recovery-origin processes expose authoritative headroom telemetry.
11. Start or release all community nodes as ordinary `run-validator` processes
    with no recovery environment. Verify admission grows from the healthy base
    under rc.8's sustained-signing headroom gate.
12. Reconcile snapshot-streaming to the chosen lineage before resuming ingest.
    Verify exact source snapshot hashes at the first replaced ordinal and the
    current tip; process health alone is insufficient.
13. Re-enable ordinary community-node restart automation only after the
    committee has positive finality margin, community nodes are draining
    normally, and snapshot-streaming follows the same lineage. If the
    environment remains configured, keep unattended automation that can
    externally restart one selected source, the complete selected cohort, or
    the fleet with that environment disabled. Automation may alert, stop
    processes, and stage preflight evidence, but an operator must explicitly
    authorize every environment-bearing coordinated start after reviewing the
    canonical anchor and snapshot-streaming boundary. The normal
    one-rollback-lead, all-other-validators launch topology remains unchanged.

No cleanup rollback/restart is required for this recovery invocation, and the
fleet must not swap back to an older jar. That is the failure mode that
previously isolated the three source nodes behind a different version gate,
advanced a private lineage, then rolled them back to an ancestor when the
fleet-compatible jar returned. The resulting abandoned ordinals cannot be
repaired automatically by snapshot-streaming. A later deliberate recovery is a
new operation and must use a newly verified canonical anchor and the entire
runbook again.

If a named process exits before the first successor, keep automation off and
treat the attempt as interrupted. Its in-process restart is deliberately
seed-free; a fresh external restart rereads the environment and can re-arm. Stop
the selected cohort and determine whether any successor was accepted. If none
was, repeat the coordinated source start only after re-verifying the same
anchor, committee, and snapshot-streaming boundary. If a successor was
accepted, do not blindly replay its old anchor. A missing non-lead can rejoin
through an ordinary validator invocation with the override omitted for that
invocation. If the rollback lead is missing, use the established operator
restart procedure with a newly verified current canonical anchor while leaving
its normal persistent `run-rollback` role unchanged. If it is uncertain whether
competing successors exist, stop and make a new canonical-lineage recovery
decision.

## Snapshot-streaming reconciliation

Snapshot-streaming must be stopped before rollback. Record both its database
tip and the ordinal/hash in `seed-snapshot.json.gz`. After GL0 stabilizes:

1. Query the direct `/global-snapshots/<N>/hash` endpoint on a quorum of the
   controlled source nodes. Do not trust a load balancer or the first responding
   node.
2. Find the first divergent ordinal `D`: prove that `D - 1` exactly matches the
   database and that `D` is the first mismatch. Do not assume `D` is
   `anchor + 1`.
3. In one reviewed database transaction, delete `global_snapshots` rows at
   ordinals `>= D`. Foreign-key cascades also remove dependent balances,
   blocks, rewards, token locks, proofs, delegated-staking data, and metagraph
   snapshots; review the deletion scope before executing it.
4. Reset `seed-snapshot.json.gz` to the last shared canonical combined snapshot
   at `D - 1`, not the new head. Head-seeding leaves a history gap.
5. Resume only after verifying contiguous database ordinals, exact hashes at
   `D` and the current tip, and zero uniqueness-violation (`23505`) errors.

This manual boundary is necessary because snapshot-streaming upserts by hash
while the database also enforces ordinal uniqueness. It cannot automatically
replace a different hash already stored at the same ordinal, and its normal
bounded reconciliation/first-responsive-node path is insufficient for incident
recovery. Hash-keyed object-store artifacts may coexist and should be recorded,
but PostgreSQL plus the resume seed are the immediate blockers.

## Metrics and alerts

New rc.9 metrics:

- `dag_consensus_recovery_seed_armed` — `1` while the local unsigned override is
  armed and `0` after an armed invocation disarms or releases its resource;
  absent on a fresh process that never had the override;
- `dag_consensus_recovery_seed_committee_size` — configured selected size for
  the lifetime of a recovery-origin invocation, reset to `0` when that
  application resource is released;
- `dag_consensus_recovery_seed_headroom_deficit` — additional selected proof
  signers required to reach next-seat headroom; logs separately name every
  selected member absent from the observed proof set;
- `dag_consensus_recovery_seed_headroom_ready` — latches to `1` after an
  accepted snapshot proves next-seat headroom, then resets when that application
  resource is released;
- `dag_consensus_recovery_seed_configured_total{role}` — startup count for
  `rollback_lead` or `planned_validator`;
- `dag_consensus_recovery_seed_disarmed_total` — successful invocation-local
  authority disarms;
- `dag_consensus_recovery_seed_headroom_pending_total` — accepted outcomes
  observed before the selected proof set reaches next-seat headroom;
- `dag_consensus_recovery_seed_headroom_reached_total` — first accepted outcome
  that reaches next-seat headroom; and
- `dag_consensus_recovery_outcome_validated_total{mode}` — exact downloaded or
  rollback outcome validations; unsigned mode is `operator_recovery_seed`.

The unsigned path deliberately reuses the signed plan's existing barrier and
therefore its existing metric names:

- `dag_consensus_recovery_plan_first_round_deferred_total`;
- `dag_consensus_recovery_plan_alignment_poll_total{aligned}`;
- `dag_consensus_recovery_plan_alignment_missing_session`;
- `dag_consensus_recovery_plan_alignment_invalid_state`;
- `dag_consensus_recovery_plan_alignment_missing_outcome`;
- `dag_consensus_recovery_plan_alignment_mismatched_outcome`;
- `dag_consensus_recovery_plan_alignment_fetch_failed`; and
- `dag_consensus_recovery_plan_alignment_error_total{stage}`.

Alert if a named node has `armed=1` after that process accepts a successor, if
any alignment gauge remains non-zero, if headroom does not become ready, or if
one selected source is externally restarted with the variable outside a
declared coordinated recovery. The variable remaining configured for future
coordinated cold restarts and the rollback lead remaining in its normal
`run-rollback` role are expected, not alert conditions.

## Compatibility with certified consensus

This rc.9 bridge is for the pre-v35 protocol inherited from rc.8. It must not be
blindly cherry-picked across the v35 activation boundary. The v35 integration
must reject unsigned anchors at or after certified-consensus activation and
reuse v35's existing pre-activation spacing guard (the unsigned anchor must be
at most `activation - 3`). A signed recovery plan remains the only explicit
fresh certified root at/after activation. Do not teach v35's canonical-root
validator to trust an unsigned seed: that would violate the invariant behind
`AuthorizedRoot`, `CertifiedRollbackRequiresRecoveryPlan`, and
`RecoveryPlanTooCloseToCertifiedActivation`.
