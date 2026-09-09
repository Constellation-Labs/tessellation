# Currency L0 deterministic history and dormant-lineage resurrection

> **Storage preflight:** do not keep same-filesystem hardlink backups of the
> live Currency snapshot `hash/` index. Rollback/download cleanup uses the
> canonical hash+ordinal link count to avoid decoding all retained history and
> to identify hash-only torn writes. Copy archives or place them on a different
> filesystem.

## Scope and activation boundary

This feature makes Currency L0 historical dependency resolution reproducible after a
JVM restart and gives an authorized solo rollback lead a deterministic way to replace a
dormant, inherited multi-peer `GlobalSnapshotSync` view.

It is a general Currency snapshot protocol transition, not a DeD- or
IntegrationNet-specific exception. It reuses these public types:

- `CurrencyIncrementalSnapshot.version`;
- `GlobalSnapshotSync`;
- `GlobalSnapshotsProcessed`; and
- the ordinary signed state-channel binary.

No new field, codec, hash algorithm, or state-proof shape is introduced. The signed
Currency artifact does change at activation: its existing `version` advances from
`0.0.1` to `1.0.0`, and version `1.0.0` assigns cumulative semantics to
`GlobalSnapshotsProcessed`. This requires the normal announced, coordinated rollout.

Two independent boundaries must not be confused:

1. the release-version join gate and deterministic consensus-config hash fence all
   members of each L0 cluster at connection time; and
2. `fields-added-ordinals.currency-snapshot-protocol-v1` is a GLOBAL L0 ordinal that
   authorizes each Currency lineage's signed `0.0.1 -> 1.0.0` transition.

The jar's SemVer (`4.1.0-rc.X`, `4.1.0`, and later releases) is not written into the
chain and does not select replay behavior. Historical replay reads the signed Currency
snapshot version. Once a lineage reaches `1.0.0`, it cannot downgrade.

Public environments deliberately omit the gate until an activation is announced;
absence resolves to `SnapshotOrdinal.MaxValue` and remains legacy. Dev activates at
global ordinal zero so the generated CI metagraph continuously exercises version
`1.0.0`.

At the first eligible Currency snapshot, transition is delayed rather than guessed if
signed Global Snapshot Info still reports an unresolved
`unappliedGlobalChangeOrdinals` entry at or below the selected Global L0 sync view.
Legacy process-local state cannot prove which such ordinals were already applied.
Entries above the selected view have not entered the Currency artifact yet and do not
block transition. Operators should nevertheless make the complete set empty before a
public activation whenever possible.

## Compatibility and deployment population

Before the announced global ordinal:

- deploy the same release and activation configuration to the complete Global L0
  fleet;
- rebuild and deploy every active Currency L0 with that release;
- update the Currency L1/data L1 applications that ship the Tessellation SDK; and
- keep dormant metagraphs offline until they are upgraded.

An old metagraph returning after activation cannot create an acceptable `0.0.1`
successor against an activation-eligible parent: upgraded validators rederive
`1.0.0`, so artifact equality fails closed. This is intentional and is why community
notice is required.

The retained-window size, sync offset, and resolved protocol activation ordinal all
participate in `deterministicConfigHash`. The advertised release-version hash is a
separate gate. Both must match; neither substitutes for the other.

## Deterministic historical dependencies

Currency artifact recreation must not depend on whether one validator happens to have
an old Global L0 snapshot on disk or can fetch it from a peer. One typed resolver covers
both historical consumers:

- the selected `GlobalSnapshotSync` target; and
- Global L0 snapshots consulted for unapplied `SpendAction`s.

The retained interval is inclusive:

```text
[parentOrdinal - (maxLastGlobalSnapshotsInMemory - 1), parentOrdinal]
```

Live processing always uses this bounded interval. An ordinal outside it returns
`outside_retention` without consulting the disk-backed Global L0 callback or the
network-backed Currency callback. An ordinal inside the interval but absent from LastN
returns `missing_recent`; that means the recent window is incomplete and must not be
papered over with node-local history.

Historical replay selects behavior from the signed Currency parent/child version:

- signed `0.0.1` history retains the legacy callback behavior so existing history
  remains reproducible; and
- signed `1.0.0` history is bounded during both live construction and replay.

Legacy validation keeps its compatibility rule that pins the signed `globalSyncView`
before comparing recreated content. Protocol `1.0.0` removes that exception: the
retained-window inputs are deterministic, so `globalSyncView` must rederive exactly.

Under `1.0.0`, the existing `GlobalSnapshotsProcessed` artifact carries processed
ordinals cumulatively while Global L0 still reports them as unapplied. Global L0
already consumes this artifact idempotently by set difference. The former
`globalSnapshotsAlreadyProcessed` ref remains only for replaying signed `0.0.1`
history; it is not an input to `1.0.0` construction.

The transition has no sentinel artifact. `SnapshotOrdinal.MaxValue` retains only its
ordinary role as the fail-closed default for an absent configuration gate.

## Dormant-lineage reset

The rollback lead completes ordinary rollback and creates its new cluster and node
sessions. Before starting solo consensus, it refreshes the canonical Global L0
parent/window, publishes one signed `GlobalSnapshotSync` through the ordinary event
mempool, and arms that exact event as mandatory in the first successor.

| Inherited signed sync view | First-successor behavior |
|---|---|
| empty | ordinary chain start (`parentOrdinal = MinValue`) |
| exactly the rollback lead | ordinary chained declaration |
| contains any other peer | dormant-lineage reset (`parentOrdinal = MinValue`) |

The operator flag authorizes emission only. Every Currency and Global L0 validator
recognizes and validates reset content independently. A reset requires:

1. the authoritative Currency proof/facilitator set is exactly the reset signer;
2. the inherited sync view contains a peer other than that signer;
3. the declaration parent is `GlobalSnapshotSyncOrdinal.MinValue`;
4. the signer's session is newer than its inherited session, when present;
5. the declared Global L0 anchor ordinal/hash is canonical, recent, and not ahead of
   the consensus Global L0 parent;
6. the target selected after `syncOffset` is in the retained window and is at or after
   `currency-snapshot-protocol-v1` activation;
7. the metagraph's last Global L0 acceptance is older than the retained window;
8. `unappliedGlobalChangeOrdinals` is empty;
9. the signer passes existing signature, facilitator, seedlist, and allowance-list
   checks; and
10. exactly one reset and no competing accepted sync declaration enters the artifact.

A valid reset atomically replaces the inherited multi-peer declaration map with the
singleton declaration and pins `globalSyncView` to the canonical target, even if the
legacy parent names a numerically newer orphaned view. It also creates a signed
protocol-`1.0.0` successor. A reset before activation fails at startup and validation.

A self-only inherited view chains normally. A newly admitted peer in a multi-member
committee can still issue its ordinary first MinValue-parent declaration because reset
shape additionally requires that peer to be the entire authoritative signer set.

The two validating layers use the strongest signer authority available in their own
state: Currency L0 checks its live facilitator set, while Global L0 can check only the
proof set carried by the signed Currency artifact. In the intended recovery, the solo
lead is both sets. The public schema does not carry an uncommitted Currency committee,
so proving more at Global L0 would require another schema field. Seedlist,
state-channel allowance-list, signature, dormancy, retained-window, and singleton-proof
checks bound this residual under the network's allowlisted-operator model.

## Durable publication protocol

Local Currency finality is not recovery success: Global L0 must include the exact
signed state-channel binary. The rollback lead therefore uses the ordinary exact-binary
outbox plus a stricter recovery receipt at
`<incremental-snapshot-path>/.recovery-sync-publication/pending.json`:

1. Before Currency persistence, prepare the non-publishable ordinary outbox entry and
   the recovery intent containing the exact binary, hashes, signed Currency artifact
   identity, mode, and Global L0 deadline.
2. Persist and read back the Currency artifact/context. Content-addressed and ordinal
   indexes must identify the exact proof-bearing artifact and matching context.
3. Mark the deadline-bearing recovery receipt locally committed.
4. Mark the ordinary publishable outbox entry committed **last**. A crash cannot publish
   through the ordinary queue while bypassing the recovery deadline.
5. Enqueue that exact binary, clear only the in-process construction guard after Currency
   outcome commit, and keep
   the durable outbox armed.
6. Repost across queue clears and JVM restarts.
7. Delete both receipts only after the exact unsigned binary hash appears in canonical
   Global L0.

Startup searches the complete retained canonical window for confirmation. An
incomplete pre-commit intent is discarded and never published. A complete exact local
artifact promotes the intent and resumes publication. A conflicting artifact at that
ordinal fails startup.

## Time bound

With `maxLastGlobalSnapshotsInMemory = 50` and `syncOffset = 2`, a reset anchored at
Global L0 ordinal `A` remains selectable through parent `A + 47`. At a 43-second
cadence this is about 33 minutes 41 seconds. Treat 30 minutes as the operational
deadline and alert with five or fewer ordinals remaining.

If confirmation does not arrive, stop the Currency cohort, preserve logs and
`pending.json`, select a new current anchor, and run a new rollback recovery. Do not
edit snapshots, keep an expired lead producing, or restart Global L0 to refresh the
deadline. Expiry leaves `refresh_pending = 1`; only exact canonical confirmation clears
it.

## Rollout and recovery runbook

### A. Announce and preflight

1. Announce the release and global activation ordinal with enough time for all active
   metagraph operators to rebuild Currency L0/L1 applications.
2. Record release tag/commit, assembly hashes, advertised version hash, deterministic
   config hash, activation ordinal, sync offset, and retention size.
3. Confirm active metagraphs have empty `unappliedGlobalChangeOrdinals`, or explicitly
   accept that their version transition will wait until every entry at or below the
   selected Global L0 view is acknowledged.
4. Keep dormant metagraph producers stopped until their full cohorts are upgraded.
5. Disable automatic restart/rollback during each coordinated cold restart.

### B. Deploy and cross activation

1. Cold-restart the complete Global L0 fleet on the same release/config.
2. Cold-restart each complete active Currency L0 cohort; never deploy mixed versions
   inside a cluster.
3. Before crossing, verify all expected nodes advertise the recorded version/config
   hashes.
4. At and after the boundary, verify new Currency artifacts carry `version = 1.0.0`,
   transition counters do not report `blocked_unproven`, and Global L0 accepts them.
5. Keep pre-activation `0.0.1` fixtures in replay tests permanently.

### C. Recover a dormant lineage

1. Stop every Currency node and select exactly one rollback lead.
2. Before authorizing a replacement, let already-submitted state-channel work drain for at
   least two Global L0 ordinals and verify this metagraph's canonical
   `lastStateChannelSnapshotHashes` value is stable. A previously signed old Currency binary
   that wins Global L0 acceptance after rollback can supersede the replacement lineage.
3. Verify the stale sync view, dormancy, empty unapplied set, canonical recent Global L0
   anchor, seedlist/allowance-list eligibility, and fee balance.
4. Start the lead with its normal rollback command plus
   `--allow-solo-consensus` on the upgraded release.
5. Verify `RECOVERY_SYNC_REFRESH_ENQUEUED`, then require both
   `construction_guard_armed = 1` and `refresh_pending = 1`.
6. After the first local successor, require `construction_guard_armed = 0` while
   `refresh_pending` remains one, and verify the successor is protocol `1.0.0`.
7. Wait for `RECOVERY_SYNC_PUBLICATION_CONFIRMED` and `refresh_pending = 0` before the
   deadline.
8. Start remaining nodes one at a time with `run-validator`; require public download,
   four-successor observation, corroborated exact-outcome handoff, synchronous registration,
   and an actual Currency proof before starting the next.
9. Remove the solo flag from the lead's next startup command and restore automation
   only after the intended multi-member facilitator list completes ordinary artifact and
   binary proof phases on consecutive successors. Currency's synchronous engine does not
   expose the Global L0 finality-margin gate.

This advances Currency and Global L0 forward. It does not roll back Global L0 and does
not require Snapshot Streaming surgery.

## Metrics

| Metric | Meaning / action |
|---|---|
| `dag_currency_l0_snapshot_protocol_total{outcome}` | `legacy`, `activated`, `deterministic`, or `blocked_unproven`; a fleet-wide blocked result requires inspecting signed unapplied history |
| `dag_currency_l0_recovery_sync_refresh_total{mode,outcome}` | Reset construction, publication, restoration, confirmation, and expiry lifecycle |
| `dag_currency_l0_recovery_sync_construction_guard_armed{mode}` | Exact reset event still required in the first local successor; do not restart or start validators |
| `dag_currency_l0_recovery_sync_refresh_pending{mode}` | Exact committed binary not yet canonically confirmed; only confirmation clears it |
| `dag_currency_l0_recovery_sync_reset_anchor_age_ordinals` | Current anchor age while unresolved |
| `dag_currency_l0_recovery_sync_selected_target_remaining_ordinals` | Retained-window headroom; alert at `<= 5` while pending |
| `dag_l0_state_channel_dependency_total{purpose,outcome}` | `recent`, `fetched`, `outside_retention`, or `missing_recent` for sync/spend dependencies |
| `dag_l0_state_channel_dependency_rejection_total{reason}` | Typed deterministic-history rejection returned to the event path |
| `dag_l0_state_channel_dependency_branch_fallback_total` | Primary root unusable; deterministic sibling attempted |
| `dag_l0_state_channel_terminal_branch_fallback_total` | Root-level typed fee/parse rejection caused sibling fallback |
| `dag_l0_state_channel_rejection_total{reason}` | Typed fee/parse rejection |
| `dag_l0_state_channel_currency_result_total{outcome}` | Accepted versus typed-rejected Currency binaries |
| `dag_currency_l0_processed_history_total{outcome}` | Cumulative `carried`, newly `processed`, or `unproven` history |

`missing_recent` on one node calls for repair of that node's retained window. A
fleet-wide rise after restart indicates systematic window thinness: investigate before
restarting anything.

## Failure posture

The feature fails closed. Wrong version transition, wrong signer set, stale session,
pre-activation reset target, noncanonical anchor, nonempty unapplied set, missing recent
snapshot, unproven processed history, competing declaration, omitted event, undurable
artifact, or mismatched outbox prevents progress rather than mutating lineage.

Two independently run solo rollback leads can still create competing Currency
histories. Exactly-one-lead coordination remains mandatory. Legacy unapplied history at
or below the selected Global L0 view cannot be repaired by guessing; it must drain under
legacy semantics or be handled by a separately designed, signed migration.

Unsupported binaries are returned to the existing event pool and may be reconsidered
on later TimeTrigger rounds. Rc.10's consumed new-intent watermark prevents that retained
backlog from creating an EventTrigger storm, but a noisy sender can still produce steady
typed-rejection work and counters. Per-address quarantine/backoff is a follow-up; it must
not alter signed ordering or silently discard a lineage.

The legacy catch-all for Currency recreation errors remains for `0.0.1` replay
compatibility. Protocol `1.0.0` gives historical dependency and processed-history
failures typed behavior, but converting every unrelated legacy recreation error into a
hard live rejection is separate work.
