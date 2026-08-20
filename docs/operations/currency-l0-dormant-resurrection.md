# Currency L0 dormant-lineage resurrection (rc.13)

## Scope and compatibility boundary

Rc.13 extends the existing one-node Currency L0 rollback recovery so a dormant
metagraph can reconnect its `GlobalSnapshotSync` lineage to the current Global
L0 chain. It reuses the existing `GlobalSnapshotSync`,
`GlobalSnapshotsProcessed`, signed state-channel binary, and snapshot types.
It introduces no consensus/wire codec, wire field, hash construction,
state-proof field, or ordinal activation. The durable publication receipt is
local operational state serialized with the repository's existing
`JsonSerializer`; it is neither gossiped nor included in signed bytes.

This is nevertheless a consensus-behavior change. A successful reset adds an
existing-schema marker to the signed Currency artifact, so that artifact and
its ordinary state proof intentionally differ from what rc.12 would derive.
Every validator of the recovering metagraph MUST therefore run rc.13 before a
reset is emitted. For IntegrationNet/DeD, the operator controls every Currency
L0 node and upgrades the complete metagraph cohort before recovery.

Global L0 also requires the normal full-fleet cold restart on a distinctly
advertised `v4.1.0-rc.13` version. Do not run mixed rc.12/rc.13 GL0 validators.
Rc.13 adds the already-existing `syncOffset` and recent-window size to the
deterministic consensus-config fingerprint; it does not add a new operator
setting. The advertised version gate and deterministic-config gate are
separate and both must agree.

There is no network-wide activation ordinal. The exact
`GlobalSnapshotsProcessed({SnapshotOrdinal.MaxValue})` artifact is a
per-lineage activation marker. Pre-marker historical replay retains rc.12
semantics; after a valid reset, every descendant carries the marker and uses
the deterministic rc.13 history rules.

Before deployment, stop the dormant metagraph producer. Confirm from signed
Global Snapshot Info that its `unappliedGlobalChangeOrdinals` set is empty.
Rc.13 deliberately fails closed when earlier spend-action processing cannot be
proven from signed Currency history. Do not infer an empty set from a local
cache.

## Recovery transition

The rollback lead completes the ordinary rollback and creates its new cluster
and node sessions. Before it starts solo consensus, it refreshes the canonical
GL0 parent/window, publishes one ordinary signed `GlobalSnapshotSync` through
the existing event-mempool path, and arms that exact event as mandatory in the
first successor.

| Inherited signed sync view | First-successor behavior |
|---|---|
| empty | ordinary chain start (`parentOrdinal = MinValue`) |
| exactly the rollback lead | ordinary chained declaration |
| contains any other peer | dormant-lineage reset (`parentOrdinal = MinValue`) |

The reset is not accepted merely because the rollback lead used an operator
flag. Every Currency and GL0 validator independently recognizes and validates
it from signed or consensus-carried inputs. Acceptance requires:

1. the authoritative Currency proof/facilitator set is exactly the reset
   signer;
2. the inherited sync view contains a peer other than that signer;
3. the declaration parent is `GlobalSnapshotSyncOrdinal.MinValue`;
4. the signer's session is strictly newer than its inherited session, when one
   exists;
5. the declared GL0 anchor ordinal/hash is in the validator's canonical recent
   window and is not ahead of the consensus GL0 parent;
6. the target selected after `syncOffset` is in that same window;
7. the metagraph's last GL0 acceptance is older than the retained window;
8. `unappliedGlobalChangeOrdinals` is empty;
9. the signer passes existing signature, facilitator, seedlist, and
   state-channel allowance-list checks; and
10. the accepted Currency artifact contains exactly one reset declaration and
    no competing accepted sync declaration.

A valid reset atomically replaces the inherited multi-peer declaration map
with the singleton declaration and pins `globalSyncView` to the reset's
validated canonical target—even if the parent names a numerically newer view
from an orphaned branch. A self-only inherited view chains normally. A newly
admitted peer in a multi-member committee can still issue its ordinary first
MinValue-parent declaration because reset recognition additionally requires
the authoritative signer set to equal that peer alone.

The rollback lead refuses to construct the first successor if the exact armed
declaration is absent, including after proposal size-cut retries. Normal
periodic sync publication is suppressed while either the construction guard or
the durable recovery publication is pending.

## Durable publication protocol

Local Currency finality is not recovery success: GL0 must include the exact
signed state-channel binary. Rc.13 therefore uses a two-phase durable outbox at
`<incremental-snapshot-path>/.recovery-sync-publication/pending.json`:

1. Before Currency persistence, write a non-publishable intent containing the
   exact signed binary, its hash/proofs hash, the exact signed Currency artifact
   identity, mode, and GL0 deadline.
2. Persist the Currency artifact and context, then read them back: both
   content-addressed and ordinal indexes must identify the exact signed
   artifact (including its proof set), and the persisted snapshot-info file
   must equal the finalized context.
3. Mark the intent locally committed, then enqueue the exact binary.
4. Clear only the process-local first-successor construction guard after the
   Currency outcome commits. The durable outbox remains armed.
5. Keep reposting across ordinary queue clears and JVM restarts.
6. Delete the outbox and set the publication-pending gauge to zero only after
   the exact unsigned binary hash appears in a canonical GL0 snapshot.

On startup, the sender checks every canonical GL0 snapshot in the retained
window, not only the current tip. This recovers the receipt when the lead died
after GL0 inclusion but before it processed that particular incremental
snapshot.

Crash behavior is fail-closed. A pre-commit intent without a complete matching
local Currency artifact and context is discarded at startup and is never
published. An exact durable artifact/context pair promotes the intent and
resumes publication. A conflicting complete artifact at that ordinal fails
startup. A committed intent is never discarded by an ordinary consensus soft
reset.

The two gauges describe different boundaries:

- `construction_guard_armed = 1`: the exact sync must still appear in the first
  locally committed Currency successor.
- `refresh_pending = 1`: the exact committed binary has not yet been observed
  in canonical GL0. Do not start the remaining metagraph validators yet.

## Deterministic historical dependencies

Currency artifact recreation must not depend on whether one validator happens
to have an old GL0 snapshot on disk or can fetch it from a peer. Rc.13 applies
one typed resolver to both historical consumers:

- the selected `GlobalSnapshotSync` target; and
- GL0 snapshots consulted for unapplied `SpendAction`s.

The retained interval is inclusive:

```
[parentOrdinal - (maxLastGlobalSnapshotsInMemory - 1), parentOrdinal]
```

Live processing and reset-epoch processing use only `LastNGlobalSnapshotStorage`.
An ordinal outside the interval yields `outside_retention` without consulting
the disk-backed GL0 callback or the network-backed Currency callback. An
ordinal inside the interval but absent from LastN yields `missing_recent` and
fails locally: that is an incomplete recent window, not evidence that a stale
lineage is acceptable. Historical replay of already-signed pre-marker history
keeps the legacy callback behavior so existing chain replay remains compatible.

The former process-local `globalSnapshotsAlreadyProcessed` cache remains only
for pre-marker historical compatibility. In the reset epoch, the existing
`GlobalSnapshotsProcessed` artifact carries processed ordinals cumulatively
while GL0 still reports them as unapplied. GL0 already consumes that artifact
idempotently by set difference. If the signed parent plus current signed GL0
state cannot prove the history, validation returns `ProcessedHistoryUnproven`
instead of guessing from memory or archives.

GL0 keeps the exact rc.12 root selector as its first choice. If that root is
uniformly rejected for a typed historical dependency or root-level fee failure,
it tries deterministic sibling content hashes. Rejected binaries are returned
through the existing state-channel event path; rc.13 adds no dead-letter store
and does not silently delete them.

## Time bound

With IntegrationNet's current values
`maxLastGlobalSnapshotsInMemory = 50` and `syncOffset = 2`, a reset anchored at
GL0 ordinal `A` remains selectable through parent `A + 47`. At a 43-second
time-trigger cadence this is approximately 33 minutes 41 seconds. Treat 30
minutes as the operational deadline and alert when five or fewer ordinals
remain.

If GL0 has not accepted the exact binary before the target leaves the window,
stop the Currency cohort, preserve logs and `pending.json`, choose a new current
anchor, and perform a new rollback recovery. Do not keep an expired lead
running, edit snapshots on disk, or restart GL0 merely to refresh the deadline.
Expiry stops retransmission but deliberately leaves `refresh_pending = 1`;
only exact canonical GL0 confirmation clears that gauge. A zero gauge therefore
cannot be confused with an expired, unsuccessful attempt.

## Runbook

### Phase A: contain and preflight

1. Stop the dormant metagraph producer and every Currency L0 node.
2. Keep telemetry collection running, but disable automated restart/rollback
   actions for GL0 and the Currency cohort.
3. Confirm GL0 is converged and advancing on the predecessor release before
   the release restart.
4. Read signed Global Snapshot Info and confirm for the metagraph:
   - `unappliedGlobalChangeOrdinals` is empty;
   - the old sync view contains the expected stale peers;
   - the lineage's last accepted GL0 ordinal is older than the retained window.
5. Record the metagraph address, last accepted Currency ordinal/hash, current
   GL0 ordinal/hash, old sync-view peer IDs, and designated rollback lead.
6. Confirm the lead is in the Currency seedlist and GL0 state-channel
   allowance list, and confirm the metagraph can pay any currently required
   state-channel fee.

### Phase B: deploy rc.13

1. Tag/build a distinct `v4.1.0-rc.13` artifact.
2. Cold-restart the complete IntegrationNet GL0 fleet on that version.
3. Keep automated restart/rollback disabled until first-round alignment
   releases and a GL0 successor finalizes.
4. Install rc.13 on every DeD Currency L0 node. Leave all Currency nodes
   stopped until the GL0 fleet is stable.

### Phase C: recover the Currency lineage

1. Start exactly one lead with its normal rollback command plus
   `--allow-solo-consensus`.
2. Verify `RECOVERY_SYNC_REFRESH_ENQUEUED`; record its mode, Currency parent,
   GL0 anchor, inherited-peer count, and valid-through ordinal.
3. Require both `construction_guard_armed = 1` and `refresh_pending = 1` before
   the first Currency round. If either is absent, stop.
4. Wait for the first Currency successor. At local commit,
   `construction_guard_armed` becomes zero while `refresh_pending` MUST remain
   one.
5. Wait for `RECOVERY_SYNC_PUBLICATION_CONFIRMED` and
   `refresh_pending = 0`, and verify GL0's canonical Currency state names the
   recovered lineage. This must occur before the 30-minute deadline.
6. Let the lead continue producing. Start each remaining node one at a time
   with normal `run-validator`; wait for download, Ready, certified admission,
   and an actual Currency proof before starting the next.
7. Remove the one-shot rollback flag from the lead's next startup command.
   Re-enable automated actions only after the multi-signer committee has
   positive finality margin.

This recovery advances Currency and GL0 forward; it does not roll back GL0 and
does not require Snapshot Streaming row surgery. A later operator-forced GL0
rollback that removes the confirmed recovery binary is outside this contract
and can orphan the recovered Currency lineage again.

## Metrics

| Metric | Meaning / action |
|---|---|
| `dag_currency_l0_recovery_sync_refresh_total{mode,outcome}` | `enqueued`, reset `accepted/rejected`, `local_committed`, `restored`, `restored_after_clear`, `gl0_confirmed`, or `expired` |
| `dag_currency_l0_recovery_sync_construction_guard_armed{mode}` | Exact event still required in the first local successor; do not restart or start validators |
| `dag_currency_l0_recovery_sync_refresh_pending{mode}` | Recovery publication is unresolved (before commit, awaiting canonical GL0 inclusion, or expired); only exact confirmation clears it; do not start validators |
| `dag_currency_l0_recovery_sync_reset_anchor_age_ordinals` | Age of the selected anchor while recovery is unresolved |
| `dag_currency_l0_recovery_sync_selected_target_remaining_ordinals` | Remaining retained-window headroom; alert at `<= 5` while pending |
| `dag_l0_state_channel_dependency_total{purpose,outcome}` | Resolver result for `sync_target` or `unapplied_spend_action`: `recent`, `fetched`, `outside_retention`, `missing_recent` |
| `dag_l0_state_channel_dependency_rejection_total{reason}` | Returned lineage due to `outside_retention`, `processed_history_unproven`, or fatal `missing_recent` |
| `dag_l0_state_channel_dependency_branch_fallback_total` | Legacy primary root was unusable and a deterministic sibling was attempted |
| `dag_l0_state_channel_terminal_branch_fallback_total` | Root-level typed fee/parse rejection caused sibling fallback |
| `dag_l0_state_channel_rejection_total{reason}` | Typed fee/parse rejection (`fee_required_unparseable`, `fee_address_missing`, `fee_balance_insufficient`) |
| `dag_l0_state_channel_currency_result_total{outcome}` | Accepted versus `typed_rejected` Currency binaries |
| `dag_currency_l0_processed_history_total{outcome}` | `carried`, newly `processed`, or `unproven` deterministic spend history |

`missing_recent` on one node calls for repair of that node's recent GL0 window.
A fleet-wide rise immediately after a cold restart indicates systematic window
thinness: investigate before restarting anything. `outside_retention` on the
old dormant branch is expected; the new reset binary has a distinct unsigned
content hash and remains eligible as a sibling.

## Failure posture

The feature is fail-closed. A bad signature, wrong signer set, stale session,
noncanonical anchor, nonempty unapplied set, missing recent snapshot, unproven
processed history, competing accepted sync declaration, omitted required event,
undurable Currency artifact, or mismatched outbox prevents progress rather than
silently mutating the sync view.

Two independently run solo rollback leads can still create competing Currency
histories. Exactly-one-lead coordination remains mandatory. The reset also
cannot repair a nonempty old unapplied-spend set without additional signed
history; that is an explicit blocker, not a best-effort path.
