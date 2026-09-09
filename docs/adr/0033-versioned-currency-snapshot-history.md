# ADR-0033: Versioned deterministic Currency snapshot history

Date: 2026-08-20

Status: Proposed

## Context

Currency snapshot recreation consumed two classes of historical Global L0 input: the
selected `GlobalSnapshotSync` target and snapshots containing unapplied spend actions.
Legacy code first searched the in-memory retained window, then used a caller callback
backed by local disk on Global L0 and by a peer fetch on Currency L0. Honest validators
with different archive layouts could therefore derive different answers for the same
signed Currency parent and event set.

The spend-action path also used a process-local
`globalSnapshotsAlreadyProcessed` ref. A warm process and a just-restarted process could
disagree about which actions had already been applied and which
`GlobalSnapshotsProcessed` artifact to emit. Neither archive availability nor process
uptime is a certified consensus input.

A long-dormant metagraph exposed both defects. Its inherited sync view selected a
Global L0 target outside the retained window. Some Global L0 validators had the old
snapshot and recreated the artifact; others could not, withdrew, and halted Global L0.
The metagraph also needed to replace a stale multi-peer sync view after a coordinated
solo rollback without introducing a network-specific exception.

An initial implementation used
`GlobalSnapshotsProcessed({SnapshotOrdinal.MaxValue})` as a one-bit lineage marker.
That was rejected: it overloaded a domain value, could encode only one transition, and
would require another workaround for the next semantics change.

## Decision

1. Use the existing signed `CurrencyIncrementalSnapshot.version` field as the persistent
   protocol-semantics identifier. Legacy history is `0.0.1`; deterministic-history
   semantics are `1.0.0`. Tessellation release SemVer is not chain data and never selects
   replay behavior.
2. Authorize `0.0.1 -> 1.0.0` with the GLOBAL L0 ordinal in
   `fields-added-ordinals.currency-snapshot-protocol-v1`. The activation value and
   retained-window parameters enter the effective consensus-config hash. Public values
   remain absent until an announced coordinated rollout; dev activates at zero.
3. Derive the child version from the signed parent version, the consensus-selected
   Global L0 sync ordinal, the activation ordinal, and signed Global Snapshot Info.
   Once `1.0.0`, a lineage never downgrades. Validators rederive the value; they do not
   trust a candidate's version merely because it is signed.
4. Delay the first transition while signed GSI reports an unresolved
   `unappliedGlobalChangeOrdinals` entry at or below the selected Global L0 sync view.
   Legacy process memory cannot prove which such entries were already applied. Entries
   above the selected view have not entered this Currency artifact yet and do not make
   the transition ambiguous. Also delay while sync selection yields
   `SnapshotOrdinal.MinValue`: that value is the no-dependency sentinel (including the
   initial `target - syncOffset` underflow boundary), not a retained Global snapshot a
   verifier can resolve. Delaying preserves the legacy chain instead of guessing or
   falling back to a caller's moving Global tip.
5. In live processing, resolve every historical Global L0 dependency exclusively from
   the inclusive retained window. `outside_retention` and `missing_recent` are typed,
   metered failures and never fall through to local disk or peer fetch.
6. During historical replay, signed `0.0.1` children preserve legacy callback behavior;
   signed `1.0.0` children remain bounded. Thus old history stays reproducible and new
   history is closed over consensus-carried inputs.
7. Under `1.0.0`, derive `GlobalSnapshotsProcessed` cumulatively from the signed parent
   artifact plus signed GSI. Carry each ordinal while GSI reports it unapplied; Global L0
   removes acknowledged ordinals by its existing idempotent set difference. The legacy
   process-local ref remains only for replaying `0.0.1` history.
8. Permit a dormant-lineage reset only at/after protocol-v1 activation. The operator
   flag authorizes emission, not validation. Reset validation uses the authoritative
   Currency signer set, inherited signed sync view, canonical retained Global L0
   ordinal/hash, session, dormancy, and empty unapplied set. A valid reset atomically
   replaces the view and creates a `1.0.0` successor.
9. Persist and retransmit the exact reset-bearing Currency binary until its unsigned
   content hash appears in canonical Global L0. Local Currency finality alone is not a
   successful cross-layer recovery.

## Why the two gates are both required

The release-version hash prevents differently advertised releases from joining an L0
cluster. `deterministicConfigHash` prevents equal release strings with divergent
consensus settings from joining. Neither tells a historical replayer which behavior was
used to create an artifact. The signed snapshot version does that, while the ordinal
gate coordinates the first legal transition.

All public networks use full-cluster cold restarts. Every active metagraph operator must
upgrade its Currency L0 cohort before the global boundary; SDK-based Currency L1/data L1
applications should be rebuilt from the same release. A dormant old metagraph must
upgrade before returning after activation.

## Safety argument

- The version transition and every reset predicate use signed or consensus-carried
  inputs. No local clock, archive inventory, process cache, or operator-only flag enters
  artifact derivation.
- Sorted protocol collections retain the repository's ordinary Circe/Hasher path. No
  canonical byte concatenation or new hash scheme is introduced.
- A malformed or premature reset is rejected independently by every validator.
- `1.0.0` replay cannot call the archive/network dependency callback.
- An unresolved legacy spend history delays transition. It cannot be silently cleared.
- Exact artifact equality includes `version`, so a downgrade, premature upgrade, or
  creator/validator mode mismatch fails closed.
- The legacy `globalSyncView` comparison exception ends at `1.0.0`; deterministic
  history must rederive that signed field exactly as well.

## Consequences

- Currency artifact bytes intentionally change at the first `1.0.0` successor, although
  the existing schema and state-proof shape do not.
- Historical `0.0.1` artifacts remain replayable.
- Active lineages transition automatically after the announced global boundary when
  their unresolved history is proven empty. This is not a one-time per-network patch.
- Dormant multi-peer lineages have a deterministic recovery route, but exactly one solo
  rollback lead remains an operational requirement.
- Currency L0 validates reset singleton authority against its facilitator set. Global
  L0 has only the signed Currency artifact proof set; the intended solo recovery makes
  those sets equal, while stronger cross-layer committee proof would require new signed
  schema.
- A metagraph with permanently unacknowledged legacy history at or below its selected
  Global L0 view cannot be upgraded by inference; it needs the legacy acknowledgment
  loop to drain or a future explicitly signed migration design.
- Future semantics changes must allocate a new signed snapshot version and an explicit
  monotonic transition. `MaxValue` must not be reinterpreted as a feature marker.

## Alternatives rejected

- **Jar SemVer in the chain:** release names describe packaging, not snapshot protocol;
  prerelease-to-final naming would make historical semantics deployment-dependent.
- **MaxValue marker artifact:** one-bit, domain-overloading, and not extensible.
- **Local archive fallback:** availability differs between honest validators.
- **Process-local migration cache:** restart history is not consensus data.
- **Immediate transition with non-empty unapplied history:** cannot be proven from legacy
  signed state and risks duplicate spend application.
