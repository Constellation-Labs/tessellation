# FieldsAddedOrdinals: ordinal-gated activation of deterministic behavior

**Status:** Reference (as-shipped)
**Scope:** the forward-compat / replay-safety primitive that gates new or changed deterministic behavior on a per-environment activation ordinal, the no-env-gating principle it enforces, and the GSI dust sweep as a worked example.

## Summary

Snapshots are signed. Once an ordinal is finalized, its artifact bytes are fixed forever, and any node that replays history (a fresh sync, a rollback, a cold restart) MUST re-derive byte-identical state or it forks. This makes shipping a fix to deterministic behavior hazardous: the new code path would change the bytes of already-signed history. `FieldsAddedOrdinals` is the primitive that resolves this. It is a record of per-environment **activation ordinals** (`config/types.scala:27-48`): each new or changed deterministic behavior is gated so that history strictly below its ordinal re-derives on the OLD path (byte-identical to what was signed), while at and after the ordinal the new behavior applies. The decision is always `ordinal >= gate`, **never** a branch on `AppEnvironment`. The values are HOCON literals packaged into the assembly jar and have no environment-variable overrides. They must be identical across the cluster and finalized before assembly. The join handshake does not compare the advertised jar hash or include these ordinals in `deterministicConfigHash`, so a mismatched ordinal is not detected before it changes consensus output.

## Mechanism

`FieldsAddedOrdinals` is a flat record of maps, one per gated behavior (`config/types.scala:27-49`):

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
  setSumFix: Map[AppEnvironment, SnapshotOrdinal],
  scFeeBalanceFromContext: Map[AppEnvironment, SnapshotOrdinal] = Map.empty,
  subTrieRoots: Map[AppEnvironment, SnapshotOrdinal] = Map.empty,
  delegatedRewardsFullCommittee: Map[AppEnvironment, SnapshotOrdinal] = Map.empty,
  feeTransactionSecurity: Map[AppEnvironment, SnapshotOrdinal] = Map.empty,
  dustSweeps: Map[AppEnvironment, SortedMap[SnapshotOrdinal, DustSweep]] = Map.empty
)
```

Each gate is loaded from the `fields-added-ordinals` HOCON block (`application.conf:210-293`). Resolution is always the same shape: pick the entry for the running environment, then compare the snapshot ordinal against it. Two value conventions appear:

- A **threshold gate** (`Map[AppEnvironment, SnapshotOrdinal]`): the behavior is gated by `ordinal >= gate`. Absent-environment resolution **fails closed** to `SnapshotOrdinal.MaxValue` (e.g. `scFeeBalanceFromContext.getOrElse(environment, SnapshotOrdinal.MaxValue)` at `GlobalSnapshotConsensus.scala:152` and `SharedServices.scala:195`): the `ordinal >= gate` check never fires, so an unset environment keeps the OLD path rather than silently activating the new one from genesis. Set an env entry to `0` to turn the new path on from genesis (as testnet does), or to a future launch ordinal to switch over at that ordinal. A high placeholder such as `9999999` does the same as the fail-closed default explicitly: it keeps the OLD path live until replaced with the real launch ordinal.
- An **exact-key gate** (`dustSweeps: Map[AppEnvironment, SortedMap[SnapshotOrdinal, DustSweep]]`): the behavior fires only at exactly the keyed ordinal (`dustSweeps.get(env).flatMap(_.get(ordinal))`), once, and never replays.

`feeTransactionSecurity` follows the replay-safe missing-environment default:
`SnapshotOrdinal.MaxValue`. Missing configuration therefore retains the historical path rather
than applying stricter validation retroactively to signed history. The shipped configuration
contains an explicit entry for every environment.

Per-environment activation ordinals differ because the same fix crosses different points of different chains. The behavior itself is identical code on every network; only WHEN it activates is per-environment. Examples from `application.conf:210-293`:

| Gate | mainnet | testnet | integrationnet | dev |
|------|---------|---------|----------------|-----|
| `tessellation-3-migration` | 4409045 | 2497000 | 3330000 | 0 |
| `fixing-allow-spend-and-token-lock-validation` | 5058096 | 9999999 | 5880000 | 0 |
| `set-sum-fix` | 9999999 | 9999999 | 5880000 | 0 |
| `sc-fee-balance-from-context` | 9999999 | 3101393 | 5880000 | 0 |
| `sub-trie-roots` | 9999999 | 9999999 | 5880000 | 9999999 |
| `delegated-rewards-full-committee` | 9999999 | 9999999 | 5880000 | 0 |
| `fee-transaction-security` | 9999999 | 9999999 | 5880000 | 0 |
| `dust-sweeps` | (none) | {3154700} | (none) | (none) |

A `9999999` entry is a not-yet-activated placeholder: the chain has not reached it, so the OLD path is still live on that environment. A `0` entry means the new path is active from genesis on that environment. An absent environment (no map entry) means the behavior never activates there.

## Reward gates: three values with different jobs

Reward-path diagnosis requires an ordinal gate and an epoch gate. They must not be
conflated with the later delegated-stake record gate:

| Value | IntegrationNet | Comparison | Effect |
|---|---:|---|---|
| `fields-added-ordinals.tessellation-3-migration` | 3,330,000 | `ordinal >= gate` | Allows `DelegateRewardsInput` and the delegated snapshot fields |
| Delegated emission `asOfEpoch` | 751,085 | `epochProgress >= asOfEpoch` | Completes the classic-to-delegated reward switch |
| `fields-added-ordinals.delegated-rewards-full-committee` | 5,880,000 | `ordinal >= gate` | Switches delegated recipients from historical evidence filtering to every Core + Tier-1 member |
| `incremental-delegated-staking-starting-ordinal` | 5,075,000 | `ordinal > gate` | Populates `currentTokenLockRef` and `currentAmount` on incremental delegated-stake records only |

The delegated reward distributor runs only when the first two conditions hold. The
third changes recipients within delegated rewards. The fourth does not select classic
versus delegated rewards. See
[Consensus reward recipients](../consensus/rewards.md).

## The no-env-gating principle

The load-bearing rule:

> New consensus functionality is ALWAYS present in the code for every network. You gate WHEN it activates by ordinal, never branch consensus behavior on `AppEnvironment`. Per-environment differences belong in the shared per-environment ordinal map, finalized before assembly. The release version and consensus config hash fence incompatible peers, but neither verifies these ordinal values.

Concretely, the read sites compare `ordinal >= gate`, never `if (environment == Mainnet)`. See `GlobalSnapshotStateChannelEventsProcessor.scala:325`:

```scala
if (snapshotOrdinal >= scFeeBalanceFromContextOrdinal)
  lastGlobalSnapshotInfo.balances.getOrElse(feeAddress, Balance.empty).pure[F]   // new path
else
  mptStore.getBalance(feeAddress).map(_.getOrElse(Balance.empty))               // old path
```

A consensus knob that is testnet-only in HOCON (so mainnet silently falls to a no-op default) **violates this principle**, because the per-environment behavior difference then lives in a runtime branch rather than in consensus-agreed state, and a future contributor cannot see, from the gate, that mainnet behaves differently. The correct way to express a per-environment consensus difference is a `Map[AppEnvironment, T]` config value that is resolved ONCE at the construction site and folded into `deterministicConfigHash` (see below). That way a divergent operator value is refused at Facility-handshake time rather than producing a silent fork.

The same discipline applies to env-keyed consensus knobs that are not ordinal gates (for example `coreCommitteeSize`, `quorumShrinkActivationViews`, `rewardRotationEpochRounds`): they are resolved per environment at one construction point and folded into `deterministicConfigHash` (`types.scala:1019-1043`), so the per-environment value is part of the consensus contract, not a runtime branch.

## Protocol and replay fences operators must not conflate

| Mechanism | What it is | Replay-relevant? | Failure mode on divergence |
|-----------|------------|------------------|----------------------------|
| **FieldsAddedOrdinals** | per-env activation ordinals for deterministic behavior changes | **Yes** | a mismatched ordinal changes artifact bytes at the boundary -> fork |
| **Tessellation and metagraph version hashes** | hashes of the reported release versions | No | a divergent value is rejected during the join handshake |
| **deterministicConfigHash** | a hash of consensus-critical knobs resolved by `SnapshotConfig.resolveEffectiveConsensusConfig` | No (it is a config FENCE, not signed into history) | L0 requires presence and exact equality at join; Facility processing also reports a mismatch; it does NOT change replayed bytes |
| **consensusSchemaVersion** | a single integer wire-version fence (`types.scala:830`, currently `34`), folded INTO `deterministicConfigHash` | No | a divergent value fences out mixed-wire-version peers at handshake; it is not signed into the snapshot artifact |
| **RegistrationRequest.jar** | an advertised artifact hash stored as peer metadata | No | no protocol rejection: `Joining.validateHandshake` does not compare it |

The distinction that matters for operators: `FieldsAddedOrdinals` is the only mechanism in this table that changes replayed history bytes. The version hashes, `deterministicConfigHash`, and `consensusSchemaVersion` are replay-irrelevant connection or declaration fences. The advertised jar hash is not a fence. Setting a wrong ordinal does not get caught at handshake; it forks the chain when the gate is crossed. That is why ordinal gates are the highest-stakes value to get right before assembly.

## Worked example: the ordinal-gated GSI dust sweep

`GlobalSnapshotDustSweep` (`GlobalSnapshotDustSweep.scala:16-150`) is a one-time, consensus-critical, post-construction state-deflation transform armed via the `dustSweeps` gate. The testnet global state is dominated by a deliberately-injected dust population (hundreds of thousands of addresses each holding exactly 12345 datum, all pure receivers with empty transaction refs). The sweep removes that sub-threshold liquid dust at a single coordinated ordinal during a network-wide cold restart, collapsing the state from roughly 80MB to roughly 1MB.

It is consensus-critical: every honest node MUST compute the identical swept `GlobalSnapshotInfo` and the identical MPT state root at the sweep ordinal, or the cluster forks. The transform is a pure function of the GSI map contents at a fixed ordinal (sorted maps, commutative datum sum), so every node at the sweep ordinal computes the identical pruned GSI and root.

### Where it wires into the acceptance path

`applyDustSweep` runs inside `GlobalSnapshotAcceptanceManager` AFTER the GSI is fully built but BEFORE the MPT sync and proof, so the returned GSI, the MPT-sync input, and `buildProof` all derive from the same swept state (`GlobalSnapshotAcceptanceManager.scala:1086-1090`):

```scala
(sweptGsi, didSweep) =
  GlobalSnapshotDustSweep.applyDustSweep(gsi, ordinal, environment, fieldsAddedOrdinals.dustSweeps)
```

Off the sweep ordinal this is a no-op (one map lookup returning `None`; `didSweep = false`), and `sweptGsi` is the same value as `gsi`, so the normal incremental MPT path is unchanged. At exactly the sweep ordinal, the MPT is rebuilt in one shot from the swept state via `syncFull` rather than streaming hundreds of thousands of incremental deletions (`GlobalSnapshotAcceptanceManager.scala:1154-1160`). `syncFull` yields the identical canonical root the incremental path would produce for the same entry set (the MPT root is a pure function of the entry set), so consensus still agrees and the producer resumes correct incremental syncs from the pruned base afterward.

### Safety gates

An address is swept only if ALL of the following hold (`GlobalSnapshotDustSweep.scala:28-44`, `:121-150`):

1. **Ordinal gate.** The sweep fires only when `dustSweeps.get(env).flatMap(_.get(ordinal))` returns a `DustSweep` (exact-key lookup). It fires exactly once at its ordinal, never replays, and an absent environment never sweeps.
2. **Dust threshold.** Only `balance.value <= threshold.value`.
3. **Empty-ref gate.** Only an address whose `lastTxRef` is absent or `TransactionReference.empty` (ordinal 0). An address that ever SENT has a non-empty ref; pruning it would reset its nonce and reopen a transaction-replay vector. The dust population is entirely pure receivers, so this gate loses zero coverage.
4. **Complete exclusion.** An address that appears as a key (including nested inner-map keys) in ANY non-balance Address-keyed GSI field is never swept (`addressesWithNonBalanceState`, `GlobalSnapshotDustSweep.scala:77-112`, 13 fields). Locking / staking / collateralizing debits the liquid `balances` entry, so a real staker can legitimately sit near zero liquid balance; sweeping their dust would be wrong.
5. **Not the collection address.** The treasury sink itself is never a sweep candidate.

The subtlety worth calling out: `lastTxRefs` is Address-keyed but is DELIBERATELY EXCLUDED from the gate-4 protected set (`GlobalSnapshotDustSweep.scala:68-72`). Every pure receiver (the entire dust population) holds an empty-ref `lastTxRefs` entry, so treating `lastTxRefs` keys as protected would exclude the whole dust population and the sweep would silently remove nothing (verified live: roughly 444k of roughly 444.5k `lastTxRefs` entries are empty). The transaction-nonce / replay concern is handled instead by the empty-ref gate (gate 3). Mishandling this one field is the difference between a working sweep and a silent no-op.

The `DustSweep` config carries the threshold and the disposition (`config/types.scala:50-61`): `collectionAddress = None` burns the collected sum (reported total supply drops), `Some(addr)` credits it to a treasury (total supply preserved).

## Second example: scFeeBalanceFromContext

`scFeeBalanceFromContext` (`config/types.scala:38-42`) is a threshold gate over the balance source used by the state-channel fee-affordability check. At and after the gate the check reads the metagraph owner's balance from the deterministic `accept()` context (`lastGlobalSnapshotInfo.balances`); below it from the pre-fix `mptStore.getBalance` path, so already-signed history re-derives byte-identically (`GlobalSnapshotStateChannelEventsProcessor.scala:325`). testnet is `3101393` -- the exact ordinal where testnet switched from the v4.0.0 `mptStore` build to the alpha.0 context build (it stalled at 3101392 on 2026-03-17 and resumed at 3101393 on 2026-04-02), so the v4.0.0 `mptStore` window below the gate replays correctly. IntegrationNet is scheduled for `5880000` with the other v4.1 gates. Mainnet retains the `9999999` placeholder until its own context-deploy ordinal is selected (`application.conf:275-284`).

## Third example: subTrieRoots

`subTrieRoots` (`config/types.scala:43-46`) is a threshold gate over the per-field MPT roots carried in `GlobalSnapshotStateProof`. Below the gate, MPT-format proofs keep the legacy shape: the overall `mptRoot` is present and the per-field proof slots remain empty. At and after the gate, those slots carry per-`GlobalStateFieldId` roots so a state-root mismatch can be localized to the divergent field (`GlobalSnapshotInfo.assembleMptProof`). This changes signed proof bytes, so each public network remains on `9999999` until a coordinated cold-restart ordinal and compatible snapshot-streaming deployment are selected; IntegrationNet is scheduled for `5880000`. `TessellationIOApp` resolves the environment entry once and passes it into `GlobalStateProofSelector`; absent environments fail closed to `SnapshotOrdinal.MaxValue`.

Unlike its sibling gates, `dev` is also `9999999` (OFF) rather than `0`. This is the only `fields-added-ordinals` gate that alters the signed `GlobalSnapshotStateProof` itself, which `snapshot-streaming` independently re-derives and validates. Its release/testnet build constructs a 1-arg `GlobalStateProofSelector` (sub-trie roots OFF), so a dev cluster signing sub-trie-enabled proofs fails `snapshot-streaming` validation on every ordinal. Keep `dev` OFF until `snapshot-streaming` honors `subTrieRootsActivationOrdinal` too (a coordinated change in the `snapshot-streaming` repo, made when a network actually activates the gate).

## Fourth example: feeTransactionSecurity

`feeTransactionSecurity` gates cryptographic authorization of metagraph data-update
`FeeTransaction`s. At and after the gate, every proof must verify over the exact bytes produced by
`FeeTransaction.serialize`, signer identities must be unique, the source wallet must participate,
and no more than 16 proofs are accepted. Below the gate, replay retains the historical
identity-only source check.

L1 submission and consensus use the latest Global Snapshot ordinal. ML0 data-block acceptance and
final snapshot acceptance use the parent Currency Snapshot's signed `globalSyncView.ordinal`.
Currency Snapshot ordinals never activate this platform rule. See
[ADR-0029](../adr/0029-fee-transaction-wallet-authorization.md).

## Operator checklist

- Ordinal gates are **consensus-critical** and must match cluster-wide. They live in `application.conf` and are packaged into the assembly jar (compile-time literals), so changing one is itself a coordinated jar redeploy.
- Before launch, replace every mainnet placeholder with the real coordinated launch ordinal:
  - `sc-fee-balance-from-context.mainnet` (`9999999`, `application.conf:275-284`): set it to its context-deploy ordinal. testnet is pinned to its real cutover (`3101393`); IntegrationNet is scheduled for `5880000`. An unset env fails closed to the `mptStore` path.
  - `sub-trie-roots.mainnet` and `.testnet` (`9999999`): set each to its proof-field activation ordinal only when that network is ready to change signed `GlobalSnapshotStateProof` bytes. IntegrationNet is scheduled for `5880000` and requires matching snapshot-streaming support. For a cold restart at checkpoint `N`, use `N + 1`.
  - `delegated-rewards-full-committee.<env>`: set the deploying environment to the first ordinal produced by the corrected jar. Below it, the historical evidence-score filter must remain available for replay.
  - `fee-transaction-security.<env>`: set the deploying environment to the first global ordinal observed only after every Currency L1 and ML0 node is upgraded. IntegrationNet is scheduled for `5880000`.
  - `dust-sweeps` has no mainnet entry yet (`application.conf:286-292`). If a mainnet sweep is intended, add one.
- For the dust sweep specifically, FINALIZE the ordinal right before deploy: it must be an ordinal the chain reaches AFTER the deflating jar is live cluster-wide. A too-early crossing on the old jar misses the sweep until a rollback re-crosses it (`application.conf:281-285`). Bump it up if the chain nears it before the coordinated cold restart completes.
- These gates do NOT participate in `deterministicConfigHash`, and the advertised jar hash is not compared, so a wrong ordinal is NOT caught at handshake. It forks the chain when the gate is crossed. Verify them by inspection before assembly and deploy the identical artifact cluster-wide.

## Key code references

| Concern | Location |
|---------|----------|
| `FieldsAddedOrdinals` record | `config/types.scala` |
| `DustSweep` config | `config/types.scala` |
| HOCON block | `application.conf` |
| Dust sweep transform | `GlobalSnapshotDustSweep.scala:16-150` |
| Dust sweep acceptance wiring + `syncFull` | `GlobalSnapshotAcceptanceManager.scala:1086-1160` |
| `scFeeBalanceFromContext` read site | `GlobalSnapshotStateChannelEventsProcessor.scala:325` |
| `scFeeBalanceFromContext` resolution | `GlobalSnapshotConsensus.scala:150`, `SharedServices.scala:193` |
| `subTrieRoots` proof assembly | `GlobalSnapshotInfo.scala:274-323` |
| `subTrieRoots` selector wiring | `TessellationIOApp.scala:117-121`, `StateProofSelector.scala:33-41` |
| `feeTransactionSecurity` signature validation | `FeeTransactionSignatureValidator.scala` |
| `feeTransactionSecurity` ML0 final-acceptance gate | `CurrencySnapshotAcceptanceManager.scala` |
| `deterministicConfigHash` folded string | `config/types.scala:950-1044` (folded string ends `:1043`) |
| `consensusSchemaVersion` | `config/types.scala:830` |
