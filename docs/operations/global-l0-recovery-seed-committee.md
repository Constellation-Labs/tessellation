# Global L0 trusted recovery seed committee

`CL_GL0_RECOVERY_SEED_COMMITTEE` is an emergency, trusted-operator override for a
Global L0 chain whose rollback anchor was finalized by a committee that is no
longer viable. It replaces only the initial operational committee at that real
incremental anchor. It does not replace the signed snapshot, snapshot context,
snapshot hash, state proof, or chain data.

This environment value is the sole explicit operator committee override. It is
intentionally not part of `versionHash`, `deterministicConfigHash`, a snapshot,
or a consensus message. Authorization is the operator's control of the selected
source-node launch environment and the coordinated cold restart.

The feature is inert when the environment variable is absent. Env-based seeding
changes no public schema and needs no activation beyond the scheduled v35
boundary. Post-v35 public verification uses the v35 certificate lineage and is
therefore unavailable before that coordinated activation. The override does
change initial consensus behavior when armed, so every node in the fleet must
run the same distinctly tagged release and effective consensus configuration.
The feature first shipped in rc.9; any later release carrying it must still be
deployed fleet-wide under its own advertised version. Mixed consensus behavior
is unsupported.

## Required topology

- Stop the entire fleet before the operation.
- Exactly one controlled source node runs `run-rollback <anchor-hash>`.
- Every other node runs the normal `run-validator` command.
- Set the same `CL_GL0_RECOVERY_SEED_COMMITTEE` value on every named recovery
  member, including the rollback lead.
- Do not set it on community validators or any node absent from the list.

> **DANGER:** This value is recovery authority, not durable configuration. A
> fresh external JVM parses it again. Comment or remove it on every selected
> source immediately after the first canonical successor commits, without
> restarting that running process. Never expose an armed source to independent
> auto-restart. The August 24 incident demonstrated that a stale seed on one
> restarted source fails against the evolved live committee and can keep the
> source unavailable until the variable is removed.

The expected IntegrationNet recovery cohort is the three controlled source
nodes. The parser accepts a comma-separated list in any order and canonicalizes
it as a `SortedSet[PeerId]`; every entry must be a unique 128-character lowercase
hex PeerId. Startup rejects an empty/malformed list, a committee smaller than
three, a local node that is absent from the list, a member outside the seedlist or configured
allowance list, a committee over the facilitator-selector cap, or a committee
that cannot prove the next seat under the deployed quorum fraction. Every
member must also satisfy collateral in the exact loaded anchor context.

A certified-from-genesis chain cannot use the canonical first incremental
snapshot (ordinal 1) as an env-recovery anchor. Its ordinary key-2 child and a
recovery-reset key-2 child have the same public lineage shape, so community
validators could not distinguish the authority safely. Startup rejects this
case before rollback storage mutation. If no successor was ever produced,
restart genesis normally; otherwise select a verified incremental anchor at
ordinal 2 or later. A future ordinal-gated activation is not subject to this
one genesis-boundary restriction, but still follows the activation-spacing
preflight below.

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

`CL_RECOVERY_CHECKPOINT_PATH` is the pre-existing fork-anchor pin. It is not a
committee recovery plan, does not name the recovery committee, and is not
distributed to or signed by community validators. The sole committee override
remains `CL_GL0_RECOVERY_SEED_COMMITTEE`.

The lead then uses the existing typed `GlobalRecoverySeedOutcome.seed`
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
path: remove the env from that validator's launch environment and restart it as
`run-validator`. Its callback cannot clear authority for a successor it never
committed. Never leave that missed validator armed while the source lineage
advances.

At disarm the running process only clears its process-local override. In-process
leave/fork restart methods never carry the unsigned override, so an unexpected
internal restart cannot silently replay the authority. It restarts that node as
an ordinary validator; the operator must treat the coordinated attempt as
interrupted and reconcile the cohort. The process does not otherwise restart,
the jar does not change, and the application does not modify a service file,
persistent launch command, or filesystem symlink. The rollback lead's normal
persistent role remains `run-rollback` even though its in-JVM recovery restart
method is the ordinary validator path.

Comment or remove the environment after the first successor. A future recovery
may deliberately re-enable the same committee only after stopping the fleet and
re-running every anchor, lineage, collateral, and Snapshot Streaming preflight.
Its rollback lead must receive the newly verified canonical incremental anchor;
reusing an old anchor can deliberately replace finalized lineage and poison
Snapshot Streaming. The launch topology remains unchanged: one controlled
`run-rollback` lead and every other node as `run-validator`.

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
8. Verify all three serve the same anchor and reconstructed outcome, the current
   alignment gauges are zero, and at least one `aligned=true` poll occurred.
   Temporary missing-session or `aligned=false` polls are expected while the
   sources start sequentially. Then wait for the first accepted successor and
   verify its ordinal, hash, and ordinary QC on every source that committed it.
   Those processes must report `dag_consensus_recovery_seed_armed == 0` and an
   incremented `dag_consensus_recovery_seed_disarmed_total`.
9. Comment/remove `CL_GL0_RECOVERY_SEED_COMMITTEE` on all selected sources
   immediately after that successor, without restarting a process that already
   committed it. Changing the persistent environment does not alter authority
   parsed by a running JVM. If a selected validator missed the successor and
   remains armed, restart only that node as an ordinary `run-validator` after
   removing the env; it must download the canonical successor before community
   release.
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
   A community validator released before the first successor cannot authenticate
   the bare synthetic root; it will fail closed in download/rejoin lifecycle and may
   force-leave after repeated failures. Do not restart-loop it or diagnose that as a
   broken source recovery. Hold it until the ordinary successor QC is public. Full-FSM
   automatic rejoin after an early release remains a pre-activation test gate.
11. Start or release all community nodes as ordinary `run-validator` processes
    with no recovery environment. Verify admission grows from the healthy base
    under rc.8's sustained-signing headroom gate.
12. Reconcile snapshot-streaming to the chosen lineage before resuming ingest.
    Verify exact source snapshot hashes at the first replaced ordinal and the
    current tip; process health alone is insufficient.
13. Re-enable ordinary community-node restart automation only after the
    committee has positive finality margin, community nodes are draining
   normally, Snapshot Streaming follows the same lineage, and the recovery
   environment has been removed from every selected source launch file.
   Automation may alert, stop processes, and stage preflight evidence, but an
   operator must explicitly authorize every future environment-bearing
   coordinated start after reviewing the canonical anchor and Snapshot
   Streaming boundary. The normal
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

In the operated three-source topology, Snapshot Streaming's configured `l0Peers`
set is the three controlled GL0 sources. `GlobalL0Service` queries that set in two
steps: responsive sources choose the candidate ordinal by bare plurality, then the
hash at that ordinal must be returned by at least `ceil((N + 1) / 2)` of the
configured sources—two matching sources when `N = 3`. A single responsive source
can steer which ordinal is attempted, but cannot satisfy the hash threshold. SS
then downloads a chain terminating at that 2-of-3 hash and applies the normal
signature, linkage, context, and state-proof validation before its
S3/PostgreSQL export path makes the snapshot available to downstream consumers.
That 2-of-3 **hash** agreement is the operated export/canonical-checkpoint boundary
used when selecting rollback anchors; it is not a substitute for GL0's own
snapshot proof quorum. Confirm the deployed SS config really names exactly the
three controlled sources—changing the configured source count changes this
threshold. Also alert if two sources are evicted after repeated pull failures:
the threshold retains the configured denominator, so SS then stalls safely until
its source pool is restored/restarted.

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
  `rollback_lead` or `selected_validator`;
- `dag_consensus_recovery_seed_disarmed_total` — successful invocation-local
  authority disarms;
- `dag_consensus_recovery_seed_headroom_pending_total` — accepted outcomes
  observed before the selected proof set reaches next-seat headroom;
- `dag_consensus_recovery_seed_headroom_reached_total` — first accepted outcome
  that reaches next-seat headroom; and
- `dag_consensus_recovery_outcome_validated_total{mode}` — exact downloaded or
  rollback outcome validations; unsigned mode is `operator_recovery_seed`; and
- `dag_consensus_certified_recovery_boundary_total{outcome}` — public v35 reset
  discovery (`detected`), structural rejection (`rejected`), and successful
  canonical-root reconstruction (`root_reconstructed`). Read reconstruction
  alongside `dag_consensus_init_download_outcome_total{outcome="success"}`;
  reconstruction alone does not mean the reset-to-tip replay was accepted.

The recovery path uses these dedicated alignment metrics:

- `dag_consensus_recovery_seed_first_round_deferred_total`;
- `dag_consensus_recovery_seed_alignment_poll_total{aligned}`;
- `dag_consensus_recovery_seed_alignment_missing_session`;
- `dag_consensus_recovery_seed_alignment_invalid_state`;
- `dag_consensus_recovery_seed_alignment_missing_outcome`;
- `dag_consensus_recovery_seed_alignment_mismatched_outcome`;
- `dag_consensus_recovery_seed_alignment_fetch_failed`; and
- `dag_consensus_recovery_seed_alignment_error_total{stage}`.

Alert if a named node has `armed=1` after that process accepts a successor, if
any alignment gauge remains non-zero, if headroom does not become ready, or if
one selected source is externally restarted with the variable outside a
declared coordinated recovery. The rollback lead remaining in its normal
`run-rollback` role is expected; the variable remaining configured after a
successful recovery is an alert condition.

## Compatibility with certified consensus

Before v35 activation, a recovery anchor must be at most `activation - 3` so
the signed controller-evidence window is rebuilt before the boundary. At or
after activation, the same env flow starts a fresh certified epoch without a
second operator artifact. The selected nodes still require exact env-derived
outcome equality and the all-member barrier. The first successor's ordinary
v35 QC then binds the public parent hash, network/domain, exact frozen
committee, Core set, and proposal value.

The live cluster's deterministic-config and allowance hashes remain mandatory
join fences, but the current certified outcome does not by itself carry a
complete historical consensus policy. Until historical policy selection or an
authenticated policy/checkpoint epoch is implemented, do not change
committee-affecting consensus config, seedlist, or allowance policy after v35
activation and expect a fresh node to replay across that change. This is an
activation blocker tracked in the v35 rollout, not authority granted to current
config during historical replay.

An unconfigured community validator walks backward from an authenticated tip
to the latest later child whose `certifiedLineage` is empty, reconstructs the
canonical root from that child's QC plus the independently validated public
parent, and replays the contiguous reset-to-tip segment through the ordinary
QC, artifact-proof, state-proof, seedlist, collateral, membership, and derived-
state validators. It needs neither the env nor a private recovery artifact. A committee of
fewer than three, a committee unable to prove its next seat, a non-seedlisted or
non-allowlisted member, an unavailable local seedlist, an ineligible member, a
mismatched parent, an invalid QC, or a non-identical derived outcome fails
closed. Repeated recoveries supersede older epochs: only the latest publicly
certified reset-to-tip segment is required.

This is a permissioned trust boundary. A quorum of colluding allowlisted
operators can certify a reset committee; they cannot do so anonymously and are
subject to the network's out-of-band operator controls. The mechanism is not a
permissionless committee-election proof.
