# FieldsAddedOrdinals: ordinal-gated activation of deterministic behavior

**Status:** Reference (as-shipped)
**Scope:** the forward-compat / replay-safety primitive that gates new or changed deterministic behavior on a per-environment activation ordinal, the no-env-gating principle it enforces, and the GSI dust sweep as a worked example.

## Summary

Snapshots are signed. Once an ordinal is finalized, its artifact bytes are fixed forever, and any node that replays history (a fresh sync, a rollback, a cold restart) MUST re-derive byte-identical state or it forks. This makes shipping a fix to deterministic behavior hazardous: the new code path would change the bytes of already-signed history. `FieldsAddedOrdinals` is the primitive that resolves this. It is a record of per-environment **activation ordinals** (`config/types.scala`): each new or changed deterministic behavior is gated so that history on the legacy side of its boundary re-derives on the OLD path (byte-identical to what was signed), while the new behavior applies on the other side. Most threshold gates use `ordinal >= gate`; a small number deliberately use `>` because the named ordinal is the last legacy observation, and dust sweeps use exact-key equality. Consensus code selects the configured ordinal for its environment and must never branch behavior directly on `AppEnvironment`. The values are HOCON configuration packaged into the assembly jar, with no dedicated environment-variable overrides for these gates. Named application configs fall back to the shared `application.conf`; packaging a named config does not by itself discard shared settings. Explicit configuration replacement remains possible, so the resolved values must agree across the cluster. Both L0 applications include every resolved `FieldsAddedOrdinals` threshold, the complete dust-sweep schedule, and the shared hashing, state-proof, and incremental-staking boundaries in `deterministicConfigHash`. The same effective configuration is used for joining and live consensus. A config fence detects disagreement, but it cannot make a unanimously wrong activation ordinal safe.

## Mechanism

`FieldsAddedOrdinals` is a flat record of maps, one per gated behavior (`config/types.scala`):

```scala
case class FieldsAddedOrdinals(
  tessellation3Migration: Map[AppEnvironment, SnapshotOrdinal],
  tessellation301Migration: Map[AppEnvironment, SnapshotOrdinal],
  checkSyncGlobalSnapshotField: Map[AppEnvironment, SnapshotOrdinal],
  metagraphSyncData: Map[AppEnvironment, SnapshotOrdinal],
  updatedLastSyncGlobalOrder: Map[AppEnvironment, SnapshotOrdinal],
  updatedLastSyncGlobalFromPeersInConsensus: Map[AppEnvironment, SnapshotOrdinal],
  updatingCombineFunctionSpendActions: Map[AppEnvironment, SnapshotOrdinal],
  fixingAllowSpendExpiration: Map[AppEnvironment, SnapshotOrdinal],
  fixingAllowSpendAndTokenLockValidation: Map[AppEnvironment, SnapshotOrdinal],
  fixingFeeTransactionBalanceOverflow: Map[AppEnvironment, SnapshotOrdinal] = Map.empty,
  dustSweeps: Map[AppEnvironment, SortedMap[SnapshotOrdinal, DustSweep]] = Map.empty,
  fixingDataApplicationFeeValidation: Map[AppEnvironment, SnapshotOrdinal] = Map.empty,
  fixingAllowSpendDestinationCredit: Map[AppEnvironment, SnapshotOrdinal] = Map.empty,
  preventingAllowSpendResurrection: Map[AppEnvironment, SnapshotOrdinal] = Map.empty,
  fixingGlobalAllowSpendExpiration: Map[AppEnvironment, SnapshotOrdinal] = Map.empty,
  removingProcessedDelegatedStakeWithdrawals: Map[AppEnvironment, SnapshotOrdinal] = Map.empty,
  tessellation41Migration: Map[AppEnvironment, SnapshotOrdinal] = Map.empty
)
```

Each gate is loaded from the `fields-added-ordinals` HOCON block (`application.conf`). Resolution first picks the entry for the running environment. The record has two value conventions:

- A **threshold gate** (`Map[AppEnvironment, SnapshotOrdinal]`): node consumers call the gate's named `...For(environment)` accessor. Those accessors share one private config-layer resolver that maps an absent environment to `SnapshotOrdinal.MaxValue`; callers do not read the raw map or choose a fallback. The threshold can therefore never be crossed in ordinary operation, and an incomplete configuration retains the OLD path rather than silently enabling new behavior from genesis. Set an explicit environment entry to `0` to activate from genesis, to a future ordinal for a new coordinated behavior change, or to the exact evidence-backed historical cutover when the gate exists to reproduce behavior already present in signed history. Each gate must document whether its comparison is `>=` or `>`; changing that comparator changes the replay boundary. A finite placeholder such as `9999999` is not the same as the missing-map sentinel: it remains deliberately dormant only until the chain reaches that value.
- An **exact-key gate** (`dustSweeps: Map[AppEnvironment, SortedMap[SnapshotOrdinal, DustSweep]]`): the behavior fires only at exactly the keyed ordinal (`dustSweeps.get(env).flatMap(_.get(ordinal))`), when that snapshot is produced or replayed; other ordinals do nothing.

Missing means disabled uniformly across every `FieldsAddedOrdinals` threshold map. There is no
in-tree threshold-gate exception. Any future exception requires an explicit per-gate rationale,
source comment, and regression test; it must not be introduced by reading a raw map or choosing a
fallback at one consumer. Exact-key maps use their named exact-key accessor and remain naturally
disabled when the environment/key is absent.

These are first-active thresholds, including historical corrections. Their disabled fallback is
not a promise that old behavior is safe for a new network: established historical cutovers must
remain explicit, and fresh-network tests explicitly select current behavior.

The separate last-legacy boundaries have different semantics. `last-kryo-hash-ordinal` selects
Kryo through the boundary (`<=`) and JSON above it; its existing missing default is `MinValue`
(Kryo at zero, JSON above zero). Preserve that comparator and default rather than applying the
first-active convention to it.

The legacy state-proof boundary and the incremental delegated-staking boundary are no longer
configured. Both are derived from the v4.1 cutover `tessellation-41-migration` (C):
`lastLegacyStateProofOrdinalFor(env)` and `incrementalDelegatedStakingStartingOrdinalFor(env)` return
`C - 1` (`0` when C is `0`). State proofs use `ordinal <= boundary` for the legacy format and
incremental staking uses `ordinal > boundary`, so both switch exactly at C. v3.5 never produced MPT
proofs or `currentTokenLockRef`/`currentAmount`; the old explicit mainnet value `5960000` came from
an aborted v4 hardfork and would have mis-replayed mainnet history. A missing cutover resolves to
`MaxValue`, which keeps both legacy forever.

Per-environment activation ordinals differ because the same fix crosses different points of different chains. The behavior itself is identical code on every network; only WHEN it activates is per-environment. Examples from `application.conf`:

testnet and integrationnet are fresh-genesised on v4.1 and carry no older history, so every
threshold is `0` there, exactly like dev; only mainnet carries v3.5 history. The mainnet values
below are signed history and must never move. `FieldsAddedOrdinalsSuite` asserts that every
non-mainnet threshold, the Kryo boundary and the fee schedule start at genesis.

| Gate | mainnet | testnet | integrationnet | dev |
|------|---------|---------|----------------|-----|
| `tessellation-3-migration` | 4409045 | 0 | 0 | 0 |
| `tessellation-301-migration` | 4915254 | 0 | 0 | 0 |
| `check-sync-global-snapshot-field` | 4488000 | 0 | 0 | 0 |
| `metagraph-sync-data` | 4915254 | 0 | 0 | 0 |
| `updated-last-sync-global-order` | 4915254 | 0 | 0 | 0 |
| `updated-last-sync-global-from-peers-in-consensus` | 4915254 | 0 | 0 | 0 |
| `updating-combine-function-spend-actions` | 4957662 | 0 | 0 | 0 |
| `fixing-allow-spend-expiration` | 5033174 | 0 | 0 | 0 |
| `fixing-allow-spend-and-token-lock-validation` | 5058096 | 0 | 0 | 0 |
| `removing-processed-delegated-stake-withdrawals` | 6176655 | 0 | 0 | 0 |
| `fixing-fee-transaction-balance-overflow` | 6814499 | 0 | 0 | 0 |
| `fixing-data-application-fee-validation` | 6818000 | 0 | 0 | 0 |
| `fixing-allow-spend-destination-credit` | 6818000 | 0 | 0 | 0 |
| `preventing-allow-spend-resurrection` | 6828500 | 0 | 0 | 0 |
| `fixing-global-allow-spend-expiration` | 6828500 | 0 | 0 | 0 |
| `tessellation-41-migration` | 9999999 (set to R+1 at cutover) | 0 | 0 | 0 |
| `dust-sweeps` | (none) | (none) | (none) | (none) |

### The v4.1 cutover gate

`tessellation-41-migration` is the cutover C: the first global ordinal produced by v4.1. Every
v4.1-only replay/state-transition rule switches on there. The former separate gates
`sub-trie-roots`, `fee-transaction-security`, `currency-snapshot-protocol-v1`,
`fixing-delegated-stake-double-withdrawal` and `fixing-spend-action-aggregate-balance` were folded into it;
their accessors (`subTrieRootsFor`, `feeTransactionSecurityFor`, `currencySnapshotProtocolV1For`,
`fixingDelegatedStakeDoubleWithdrawalFor`, `fixingSpendActionAggregateBalanceFor`) remain and resolve to C. The legacy state-proof and
incremental-staking boundaries are derived as `C - 1`.

At the mainnet v3.5 -> v4.1 cold-restart cutover two ordinals are pinned, and they differ on purpose.
Let R be the final v3.5 global snapshot (the rollback anchor):

1. `fields-added-ordinals.tessellation-41-migration.mainnet = R + 1` (`application.conf`). R itself
   replays as v3.5.
2. `snapshot.certified-consensus-activation-ordinal.mainnet = R` (`dag-l0.conf`, exact key, at most
   the rollback anchor).

The restart uses the recovery-seed procedure (`CL_GL0_RECOVERY_SEED_COMMITTEE` = the source nodes,
lead started with `run-rollback` at R). Until then the mainnet placeholder `9999999` keeps every
v4.1-only rule off and every derived boundary on the legacy side.

Gates whose legacy path never produced mainnet history were deleted rather than folded:
`set-sum-fix` (its only output was never read), `sc-fee-balance-from-context` (state-channel fee
and staking balances always come from the acceptance context, as on release/mainnet) and
`delegated-rewards-full-committee` (delegated rewards always follow the frozen committee).

A `9999999` entry is a not-yet-activated placeholder only while the chain remains below it. A `0`
entry means the new path is active from genesis on that environment. An absent threshold mapping
resolves to `SnapshotOrdinal.MaxValue` and is the repository's disabled convention; an absent
exact-key sweep means no sweep is scheduled.

## Regression coverage and consumer alignment

`FieldsAddedOrdinalsSuite` compares the packaged configuration with an independently reviewed
expected table for every environment, including intentional absences, historical boundaries, the
derived cutover boundaries, and the complete dust schedule. New gates must be added to that table. The shared test fixture enables
all current thresholds; tests of historical behavior must explicitly override their boundary.
`ConsensusOrdinalConfigSuite` checks that changing or removing activation values changes the
consensus hash, and that unrelated environments and configuration ordering do not.

The record has more fields than pureconfig's `forProductN` readers (and Scala 2 functions) support,
so `fieldsAddedOrdinalsReader` in `ext/pureconfig` builds it with named arguments. A new gate needs a
named entry there as well as in `resolvedThresholdsFor`. `FieldsAddedOrdinalsReaderSuite` gives every
field a distinct ordinal per environment and checks that each field is read from its own kebab-case
key, that a missing key fails loading, and that unknown keys are ignored.

Snapshot Streaming embeds the node libraries. The CI build applies
`docker/snapshot-streaming/snapshot-streaming-state-proof.patch` (apply-or-fail), which reads the
derived boundaries and the sub-trie activation through the same `*For` accessors as Global L0, so a
missing environment resolves identically on both sides. Every public deployment of Snapshot Streaming
still needs its own qualified artifact before the cutover.

## Reward gates: three values with different jobs

Reward-path diagnosis requires an ordinal gate and an epoch gate. They must not be
conflated with the later delegated-stake record gate:

| Value | Mainnet | Comparison | Effect |
|---|---:|---|---|
| `fields-added-ordinals.tessellation-3-migration` | 4,409,045 | `ordinal >= gate` | Allows `DelegateRewardsInput` and the delegated snapshot fields |
| Delegated emission `asOfEpoch` | 2,311,565 | `epochProgress >= asOfEpoch` | Completes the classic-to-delegated reward switch |
| Incremental delegated-staking boundary (derived, `C - 1`) | placeholder | `ordinal > boundary` | Populates `currentTokenLockRef` and `currentAmount` on incremental delegated-stake records only |

The delegated reward distributor runs only when the first two conditions hold. The
third does not select classic versus delegated rewards. Delegated rewards always pay every
member of the frozen signing committee. See
[Consensus reward recipients](../consensus/rewards.md).

## The no-env-gating principle

The load-bearing rule:

> New consensus functionality is ALWAYS present in the code for every network. You gate WHEN it activates by ordinal, never branch consensus behavior on `AppEnvironment`. Per-environment differences belong in the shared per-environment ordinal map, finalized before assembly. The release version rejects different software versions; the consensus config hash also rejects disagreement about the resolved activation values. Neither proves that a value agreed by every node is historically correct.

Concretely, the read sites compare `ordinal >= gate`, never `if (environment == Mainnet)`. See `GlobalSnapshotAcceptanceManager.scala`:

```scala
val removingProcessedWithdrawals =
  ordinal >= fieldsAddedOrdinals.removingProcessedDelegatedStakeWithdrawalsFor(environment)
```

A consensus knob that is testnet-only in HOCON (so mainnet silently falls to a no-op default) **violates this principle**, because the per-environment behavior difference then lives in a runtime branch rather than in consensus-agreed state, and a future contributor cannot see, from the gate, that mainnet behaves differently. The correct way to express a per-environment consensus difference is a `Map[AppEnvironment, T]` config value that is resolved ONCE at the construction site and folded into `deterministicConfigHash` (see below). That way a divergent operator value is rejected during L0 joining rather than producing a silent fork.

The same discipline applies to env-keyed consensus knobs that are not ordinal gates (for example `coreCommitteeSize`, `activeAdmissionMinProbationReentrySlots`, `certifiedConsensusActivationKey`): they are resolved per environment at one construction point and folded into `deterministicConfigHash` (`types.scala`), so the per-environment value is part of the consensus contract, not a runtime branch.

## Protocol and replay fences operators must not conflate

| Mechanism | What it is | Replay-relevant? | Failure mode on divergence |
|-----------|------------|------------------|----------------------------|
| **FieldsAddedOrdinals** | per-env activation ordinals for deterministic behavior changes | **Yes** | a mismatched ordinal changes artifact bytes at the boundary -> fork |
| **Tessellation and metagraph version hashes** | hashes of the reported release versions | No | a divergent value is rejected during the join handshake |
| **deterministicConfigHash** | a hash of the effective consensus settings, including the shared activation configuration added by `ConsensusConfig.withSharedConfig` | It binds live declarations/trigger statements; it does not select historical snapshot derivation | L0 requires presence and exact equality at join; Facility processing also reports a mismatch; it does NOT change replayed bytes |
| **consensusSchemaVersion** | a single integer wire-version fence (currently `36`), folded INTO `deterministicConfigHash` | No | a divergent value fences out mixed-wire-version peers at handshake; it is not signed into the snapshot artifact |
| **certifiedConsensusActivationKey** | the environment-resolved first key Global L0 may produce (this build has no pre-v35 engine), folded INTO `deterministicConfigHash` | Yes for active consensus behavior, but not a public snapshot field | a mismatched value fences at config/Facility checks; keys below it are only downloaded/replayed, never produced, and a rollback anchor below it is rejected |
| **currencySnapshotProtocolV1** | Global-ordinal authorization for the signed Currency snapshot `0.0.1 -> 1.0.0` semantics transition; its resolved value is copied into `ConsensusConfig` | **Yes** | divergent values are fenced at joining; crossing the agreed global key changes Currency artifact bytes and replay mode |
| **RegistrationRequest.jar** | an advertised artifact hash stored as peer metadata | No | no protocol rejection: `Joining.validateHandshake` does not compare it |

The distinction that matters for operators: ordinal gates (`FieldsAddedOrdinals` and the per-L0 `certifiedConsensusActivationKey`) switch deterministic behavior at a replay key. Version hashes and `consensusSchemaVersion` are connection/declaration fences; `deterministicConfigHash` is now a hard L0 join fence over the exact resolved activation value and other consensus-critical settings, but it does not make a wrong cluster-wide ordinal safe. The advertised jar hash is not a fence. A unanimously wrong ordinal can pass config checks and still switch behavior at the wrong key, so ordinal gates remain the highest-stakes values to verify before assembly.

## Worked example: the ordinal-gated GSI dust sweep

`GlobalSnapshotDustSweep` (`GlobalSnapshotDustSweep.scala`) is a one-time, consensus-critical, post-construction state-deflation transform armed via the `dustSweeps` gate. The testnet global state is dominated by a deliberately-injected dust population (hundreds of thousands of addresses each holding exactly 12345 datum, all pure receivers with empty transaction refs). The sweep removes that sub-threshold liquid dust at a single coordinated ordinal during a network-wide cold restart, collapsing the state from roughly 80MB to roughly 1MB.

It is consensus-critical: every honest node MUST compute the identical swept `GlobalSnapshotInfo` and the identical MPT state root at the sweep ordinal, or the cluster forks. The transform is a pure function of the GSI map contents at a fixed ordinal (sorted maps, commutative datum sum), so every node at the sweep ordinal computes the identical pruned GSI and root.

### Where it wires into the acceptance path

`applyDustSweep` runs inside `GlobalSnapshotAcceptanceManager` AFTER the GSI is fully built but BEFORE the MPT sync and proof, so the returned GSI, the MPT-sync input, and `buildProof` all derive from the same swept state (`GlobalSnapshotAcceptanceManager.scala`):

```scala
(sweptGsi, didSweep) =
  GlobalSnapshotDustSweep.applyDustSweep(gsi, fieldsAddedOrdinals.dustSweepFor(environment, ordinal))
```

Off the sweep ordinal this is a no-op (one map lookup returning `None`; `didSweep = false`), and `sweptGsi` is the same value as `gsi`, so the normal incremental MPT path is unchanged. At exactly the sweep ordinal, the MPT is rebuilt in one shot from the swept state via `syncFull` rather than streaming hundreds of thousands of incremental deletions (`GlobalSnapshotAcceptanceManager.scala`). `syncFull` yields the identical canonical root the incremental path would produce for the same entry set (the MPT root is a pure function of the entry set), so consensus still agrees and the producer resumes correct incremental syncs from the pruned base afterward.

### Safety gates

An address is swept only if ALL of the following hold (`GlobalSnapshotDustSweep.scala`, `:121-150`):

1. **Ordinal gate.** The sweep fires only when `dustSweeps.get(env).flatMap(_.get(ordinal))` returns a `DustSweep` (exact-key lookup). It fires at its configured ordinal during production or historical replay; an absent environment never sweeps.
2. **Dust threshold.** Only `balance.value <= threshold.value`.
3. **Empty-ref gate.** Only an address whose `lastTxRef` is absent or `TransactionReference.empty` (ordinal 0). An address that ever SENT has a non-empty ref; pruning it would reset its nonce and reopen a transaction-replay vector. The dust population is entirely pure receivers, so this gate loses zero coverage.
4. **Complete exclusion.** An address that appears as a key (including nested inner-map keys) in ANY non-balance Address-keyed GSI field is never swept (`addressesWithNonBalanceState`, `GlobalSnapshotDustSweep.scala`, 14 fields). Locking / staking / collateralizing debits the liquid `balances` entry, so a real staker can legitimately sit near zero liquid balance; sweeping their dust would be wrong.
5. **Not the collection address.** The treasury sink itself is never a sweep candidate.

The subtlety worth calling out: `lastTxRefs` is Address-keyed but is DELIBERATELY EXCLUDED from the gate-4 protected set (`GlobalSnapshotDustSweep.scala`). Every pure receiver (the entire dust population) holds an empty-ref `lastTxRefs` entry, so treating `lastTxRefs` keys as protected would exclude the whole dust population and the sweep would silently remove nothing (verified live: roughly 444k of roughly 444.5k `lastTxRefs` entries are empty). The transaction-nonce / replay concern is handled instead by the empty-ref gate (gate 3). Mishandling this one field is the difference between a working sweep and a silent no-op.

The `DustSweep` config carries the threshold and the disposition (`config/types.scala`): `collectionAddress = None` burns the collected sum (reported total supply drops), `Some(addr)` credits it to a treasury (total supply preserved).

## Second example: sub-trie roots (v4.1 cutover)

`subTrieRootsFor` (`config/types.scala`) resolves to the v4.1 cutover and selects the per-field MPT roots carried in `GlobalSnapshotStateProof`. Below the gate, MPT-format proofs keep the legacy shape: the overall `mptRoot` is present and the per-field proof slots remain empty. At and after the gate, those slots carry per-`GlobalStateFieldId` roots so a state-root mismatch can be localized to the divergent field (`GlobalSnapshotInfo.assembleMptProof`). This changes signed proof bytes, so mainnet keeps its placeholder until the cutover is pinned and a compatible Snapshot Streaming deployment is ready. testnet and integrationnet start at genesis. `TessellationIOApp` resolves the environment entry once and passes it into `GlobalStateProofSelector`; absent environments fail closed to `SnapshotOrdinal.MaxValue`.

Development activates this gate at ordinal `0` so CI exercises the signed proof shape. The Tessellation build applies the matching Snapshot Streaming compatibility patch: both SS entry points resolve the two-argument `GlobalStateProofSelector`, and proof validation reuses `GlobalSnapshotInfo.assembleMptProof` instead of constructing an mpt-root-only literal. This is compatibility evidence only; every public environment still requires a separately versioned, tested, and deployed Snapshot Streaming artifact before its gate is crossed (or before resuming from an already-post-gate checkpoint).

## Third example: fee transaction security (v4.1 cutover)

`feeTransactionSecurityFor` resolves to the v4.1 cutover and enables source-authorized co-signers
on metagraph data-update `FeeTransaction`s. Proof verification itself started earlier on mainnet:
from `fixing-data-application-fee-validation` (6818000, release/mainnet #1577) every proof must verify
over the exact bytes produced by `FeeTransaction.serialize`, signer identities must be unique and no
more than 16 proofs are accepted, and every proof must still belong to the source wallet. Below
6818000, replay retains the historical identity-only source check.

L1 submission and consensus use the latest Global Snapshot ordinal. ML0 data-block acceptance and
final snapshot acceptance use the parent Currency Snapshot's signed `globalSyncView.ordinal`.
Currency Snapshot ordinals never activate this platform rule. See
[ADR-0029](../adr/0029-fee-transaction-wallet-authorization.md).

## Operator checklist

- Ordinal gates are **consensus-critical** and must match cluster-wide. They live in shared HOCON configuration packaged into the assembly. Deployments use one software version and a full-cluster cold restart; version mismatches are rejected at joining. The config hash additionally checks agreement among nodes running that version.
- At the mainnet v3.5 -> v4.1 cutover, pin the two cutover ordinals described in "The v4.1 cutover gate": `tessellation-41-migration.mainnet = R + 1` and `certified-consensus-activation-ordinal.mainnet = R`. That single change activates sub-trie roots, fee transaction security, Currency snapshot protocol 1.0.0, unique delegated-stake settlement (#1593, composed with the #1498 processed-withdrawal removal), MPT state proofs and incremental staking.
- Remaining per-gate placeholders:
  - `dust-sweeps` has no entry on any network (`application.conf`). If a sweep is intended, add one.
- For the dust sweep specifically, FINALIZE the ordinal right before deploy: it must be an ordinal the chain reaches AFTER the deflating jar is live cluster-wide. A too-early crossing on the old jar misses the sweep until a rollback re-crosses it (`application.conf`). Bump it up if the chain nears it before the coordinated cold restart completes.
- Every resolved threshold in `FieldsAddedOrdinals`, the full dust-sweep schedule (ordinals, thresholds, and burn/credit destinations), the Kryo boundary and the two derived state-proof/staking boundaries enter both L0 config hashes. Only the running environment is included. Currency protocol-v1 retains its existing explicit activation field as well. The advertised jar metadata hash is still not a substitute for either the release-version gate or this config fence. A unanimously wrong ordinal remains dangerous even when every node reports the same hash, so verify gates by inspection before assembly and deploy the identical artifact cluster-wide.

## Key code references

| Concern | Location |
|---------|----------|
| `FieldsAddedOrdinals` record | `config/types.scala` |
| `DustSweep` config | `config/types.scala` |
| HOCON block | `application.conf` |
| Dust sweep transform | `GlobalSnapshotDustSweep.scala` |
| Dust sweep acceptance wiring + `syncFull` | `GlobalSnapshotAcceptanceManager.scala` |
| v4.1 cutover and derived boundaries | `config/types.scala` (`tessellation41MigrationFor`, `tessellation41LastLegacyOrdinalFor`) |
| Sub-trie proof assembly | `GlobalSnapshotInfo.scala` |
| Sub-trie selector wiring | `TessellationIOApp.scala`, `StateProofSelector.scala` |
| Fee transaction signature validation | `FeeTransactionSignatureValidator.scala`, node-shared `FeeTransactionValidator.scala` (`FeeTransactionSignerPolicy`) |
| Fee transaction ML0 final-acceptance gate | `CurrencySnapshotAcceptanceManager.scala`, `BalanceOpsManager.scala` |
| Processed delegated-stake withdrawal removal (#1498) | `GlobalSnapshotAcceptanceManager.scala`, `DelegatedStakeStateManager.scala` |
| Currency snapshot protocol transition | `CurrencySnapshotSemantics.scala`, `CurrencySnapshotAcceptanceManager.scala` |
| `deterministicConfigHash` folded string | `config/types.scala` (`ConsensusConfig.deterministicConfigHash`) |
| `consensusSchemaVersion` | `config/types.scala` (`ConsensusConfig`) |
| `certifiedConsensusActivationOrdinal` | `config/types.scala` (`SnapshotConfig`) |
