# 35. v3.5 to v4.1 mainnet migration through a single cutover

<!--
Number checked against docs/adr/ on origin/develop (2026-10-10): 0035 was unused. Recheck after the
final rebase.
-->

Date: 2026-10-10

## Status

Proposed

Amends [ADR-0018](0018-supermajority-quorum-and-between-round-eviction.md),
[ADR-0021](0021-quorum-shrink-and-finality-floor.md),
[ADR-0025](0025-mpt-replay-safety-and-divergence-prevention.md),
[ADR-0028](0028-delegated-validator-reward-recipients.md),
[ADR-0029](0029-fee-transaction-wallet-authorization.md),
[ADR-0030](0030-broad-tier1-signing-leases-and-canonical-admission.md),
[ADR-0032](0032-certified-consensus-outcomes.md),
[ADR-0033](0033-versioned-currency-snapshot-history.md) and
[ADR-0034](0034-consensus-schema-change-governance.md). Each of those carries a dated
amendment section pointing here. Implemented by PR #1627.

## Context

- Mainnet runs v3.5 (release/mainnet). Its tip was about 7,033,358 when this ADR was written.
  No public network carries v4 history that must be preserved: testnet and integrationnet are
  restarted from a fresh v4.1 genesis, so the only history v4.1 must replay is mainnet's v3.5
  history.
- develop had accumulated one activation ordinal per v4.1-only rule (`sub-trie-roots`,
  `fee-transaction-security`, `currency-snapshot-protocol-v1`,
  `fixing-delegated-stake-double-withdrawal`, `fixing-spend-action-aggregate-balance`,
  `delegated-rewards-full-committee`, `sc-fee-balance-from-context`, `set-sum-fix`, plus the
  explicit `last-legacy-state-proof-ordinal` and `incremental-delegated-staking-starting-ordinal`
  boundaries). Each needed its own per-environment pin, and the explicit mainnet value `5960000`
  for the two boundaries came from an aborted v4 hardfork; it does not describe mainnet history.
- develop also still carried the pre-v35 Global L0 engine (legacy view-change policy, artifact
  vote lock, immediate-sign path, quorum-denominator shrink rung, trailing-common-ancestor filter,
  legacy witness-pool certificate validation) so that a network could cross
  `certified-consensus-activation-ordinal` from legacy rounds, through the ADR-0032 section 8
  exact-key activation bridge.
- That bridge seeds the first certified committee by reconstructing round A-1 from the signed
  legacy artifact's `peerHistory`. v3.5 snapshots carry no `peerHistory`, so the bridge cannot
  fire on mainnet. With testnet and integrationnet certified from genesis, no network can use it.
- release/mainnet carried behavior develop was missing (#1498 processed delegated-stake
  withdrawal removal, #1577 fee-proof verification, allow-spend expiry selection, seedlist
  readmits, snapshot timeouts). Without those, develop could not reproduce signed mainnet state.

## Decision

### Networks

- dev, testnet and integrationnet start at genesis on v4.1. Every `fields-added-ordinals`
  threshold is `0` and `snapshot.certified-consensus-activation-ordinal` is `0` on those
  environments (`modules/node-shared/src/main/resources/application.conf`,
  `modules/dag-l0/src/main/resources/dag-l0.conf`). `FieldsAddedOrdinalsSuite` enforces this.
- Mainnet keeps every historical value it has already crossed. Those values are signed history
  and never move.

### One cutover gate C

`fields-added-ordinals.tessellation-41-migration` (C) is the first global ordinal produced by
v4.1. Every v4.1-only replay/state-transition rule switches on at C:

- Folded into C (accessors kept, each resolves to C; `FieldsAddedOrdinals` in
  `node-shared/.../config/types.scala`): `sub-trie-roots` (`subTrieRootsFor`),
  `fee-transaction-security` (`feeTransactionSecurityFor`), `currency-snapshot-protocol-v1`
  (`currencySnapshotProtocolV1For`), `fixing-delegated-stake-double-withdrawal`
  (`fixingDelegatedStakeDoubleWithdrawalFor`) and `fixing-spend-action-aggregate-balance`
  (`fixingSpendActionAggregateBalanceFor`).
- Derived from C, keys removed: `lastLegacyStateProofOrdinalFor` and
  `incrementalDelegatedStakingStartingOrdinalFor` both return `tessellation41LastLegacyOrdinalFor`,
  which is C-1, `MinValue` when C is `0`, and `MaxValue` when C is disabled. State proofs use
  `<=` for legacy and incremental staking uses `>`, so both switch exactly at C. Both values remain
  inputs to `ordinalConfigHashFor`.
- Deleted, because their legacy path never produced mainnet history: `sc-fee-balance-from-context`
  (state-channel fee and staking balances always come from the acceptance context, as v3.5 does),
  `set-sum-fix` (its only output, `DelegatedRewardsResult.totalEmittedRewardsAmount`, was never
  read), `delegated-rewards-full-committee` together with the legacy evidence-score reward recipient
  filter (delegated rewards always pay the frozen Core + Tier-1 committee), and the testnet
  dust-sweep schedule (the `dust-sweeps` mechanism stays; no sweep is scheduled on any network).

### Certified activation A = R through the recovery seed

Mainnet enters certified consensus (ADR-0032) through the existing env-only recovery-seed root,
not through an activation bridge. Let R be the final v3.5 global snapshot ordinal. Two ordinals are
pinned together at the cutover, and they differ on purpose:

1. `snapshot.certified-consensus-activation-ordinal.mainnet = R` (`dag-l0.conf`). R is also the
   rollback anchor.
2. `fields-added-ordinals.tessellation-41-migration.mainnet = R + 1` (node-shared
   `application.conf`). R itself still replays with v3.5 rules; R + 1 is the first v4.1 snapshot.

Both are the placeholder `9999999` until the cutover is pinned. The procedure recorded beside the
activation key in `dag-l0.conf`: halt v3.5 and confirm the source nodes share tip R and its hash;
start the lead source node with `run-rollback --rollback-hash H(R)` and the other source nodes with
`run-validator`, all with `CL_GL0_RECOVERY_SEED_COMMITTEE` set to the source-node committee; wait
until R + 1 is certified, R + 2 carries its QC and
`dag_consensus_recovery_seed_boundary_publicly_durable == 1`; then unset the environment variable
on the source nodes (it re-arms on every fresh JVM). Community validators download from the public
recovery root and re-enter through certified open admission.

### Removed consensus code

- The pre-v35 Global L0 engine: `ViewSafetyMode` and the legacy view-change policy
  (`FreezeAfterVote` / `PreserveLegacy`), the artifact-only `VoteLock` and the lock-on-vote
  protocol (`tryLockVote`), the legacy immediate-sign path, legacy trigger selection and same-key
  restart guards, `TrailingCommonAncestorFilter`, and the legacy witness-pool VCC/TC validation in
  `ProposalVccValidator`. `CertifiedVoteLock` and its persistence are unchanged.
- `QuorumDenominatorShrink`. The ADR-0021 finality floor is kept in `FinalityQuorum.scala`, and
  Global L0's `clusterFloorActive` is always `true`, bootstrap included
  (`GlobalSnapshotConsensusStateAdvancer.scala`).
- The exact-key activation bridge: `resetLegacyOutcome*`, the activation committee checks,
  `certifiedActivationCommittee`, and the A-1 activation-parent reconstruction in
  `GlobalCertifiedDownloadValidator`. A non-genesis activation now requires a public recovery root
  (`certified_recovery_root_required`).
- Config knobs `quorum-shrink-activation-views`, `lock-on-vote-protocol-version` and
  `min-participation-in-window`, and the GL0-only parameters in `currency-l0.conf`.
  `max-facilitator-count` (1000) and `active-admission-recent-signer-window` (10) became scalars.
- Metrics `dag_consensus_quorum_shrink_*`, `dag_consensus_same_key_restart_suppressed_total`,
  `dag_consensus_legacy_locked_view_change_suppressed_total`,
  `dag_consensus_locked_lagging_recovery_probe_total`,
  `dag_consensus_abandon_skipped_same_key_lock_total` and
  `dag_consensus_binary_finality_view_change_suppressed_total`. The rejections
  `vcc_voter_not_in_pool` / `tc_voter_not_in_pool` now surface as `vcc_under_quorum` /
  `tc_under_quorum`.

### Fail-closed guards (`dag-l0` `Main.scala`, `GlobalSnapshotConsensusStateCreator.scala`)

- Startup refuses an environment with no `certified-consensus-activation-ordinal` entry
  (`CertifiedConsensusActivationUnconfigured`) and `run-genesis` with an activation above the first
  incremental ordinal (`GenesisBeforeCertifiedActivation`).
- Every rollback anchor must be at or after activation (`RollbackAnchorBelowCertifiedActivation`).
- A node never produces a round below activation (`CertifiedConsensusNotActiveForProduction`). It
  still downloads and replays history below activation.

### Ported from release/mainnet

- #1498 `removing-processed-delegated-stake-withdrawals` (mainnet 6176655): an orphaned expired
  withdrawal is skipped and every expired withdrawal is removed from pending; below the gate an
  orphan fails as v3.5 did.
- #1577 fee-proof verification from `fixing-data-application-fee-validation` (mainnet 6818000),
  selected through `FeeTransactionSignerPolicy` (`LegacyExclusiveSource`,
  `VerifiedExclusiveSource`, then `VerifiedSourceAuthorized` from C).
- Mainnet allow-spend expiry selection, seedlist readmits (#1620) and snapshot timeouts.

### ADR-0034 classification

Replay/state-transition (class 2), plus a narrow class 3:

- Class 2: every correction keeps the historical path behind a mainnet ordinal, and every mainnet
  value for a gate mainnet has already crossed is unchanged.
- Class 3, SDK-visible types: the `FieldsAddedOrdinals` config type loses the folded and deleted
  fields and gains `tessellation41Migration`; `SharedConfig` / `SharedConfigReader` and the
  `SnapshotOrdinalConfig` trait drop the `lastLegacyStateProofOrdinal` and
  `incrementalDelegatedStakingStartingOrdinal` maps (their `...For(environment)` accessors remain,
  now derived). Three node-shared interfaces that the SDK exposes also
  change: `FeeTransactionValidator.validate` takes a `FeeTransactionSignerPolicy` instead of a
  `Boolean`; `DelegatedRewardsResult` drops `totalEmittedRewardsAmount`;
  `SpendActionValidator.validateReturningAcceptedAndRejected` returns every rejection per
  metagraph (`Map[Address, List[(SpendAction, List[SpendActionValidationError])]]`). The #1627
  description records that no metagraph is known to implement or construct these; one that does
  must change its source, not only rebuild. scas approved breaking SDK source compatibility for
  v4.1.
- Class 3, join fence: `consensusSchemaVersion` 35 -> 36 because `deterministicConfigHash` inputs
  changed. It only fences develop builds from each other.
- Unchanged: snapshot and `GlobalSnapshotInfo` fields, state-proof construction, codecs,
  hash/signature preimages, persisted formats and P2P payload shapes.

## Consequences

- One ordinal, C, moves every v4.1-only rule on mainnet. A forgotten gate can no longer leave one
  rule on the v3.5 side after the cutover.
- Test networks have no historical boundaries left to maintain; regressions in those paths only
  matter for mainnet replay.
- Mainnet validators that are not in the recovery-seed committee do not sign or earn validator
  rewards until certified open admission admits them after the cutover.
- Rollback below R is impossible after the cutover: this build has no engine to produce rounds below
  activation, and startup rejects such an anchor.
- Kryo-era download replay is a known bug and is out of scope here. A full mainnet replay from the
  first incremental snapshot, and a non-dev end-to-end run of the recovery-seed cutover, are
  follow-ups.
- Snapshot Streaming must ship a build that reads the derived boundaries and `subTrieRootsFor`
  through the same accessors before C is pinned (`docs/operations/fields-added-ordinals.md`).
- Under more than `f` failures of the frozen committee Global L0 halts rather than shrinking the
  quorum denominator; recovery is a coordinated restart.
