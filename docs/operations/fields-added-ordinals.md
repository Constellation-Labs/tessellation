# FieldsAddedOrdinals: ordinal-gated activation of deterministic behavior

**Status:** Reference (as-shipped)
**Scope:** the forward-compat / replay-safety primitive that gates new or changed deterministic behavior on a per-environment activation ordinal, the no-env-gating principle it enforces, and the GSI dust sweep as a worked example.

## Summary

Snapshots are signed. Once an ordinal is finalized, its artifact bytes are fixed forever, and any node that replays history (a fresh sync, a rollback, a cold restart) MUST re-derive byte-identical state or it forks. This makes shipping a fix to deterministic behavior hazardous: the new code path would change the bytes of already-signed history. `FieldsAddedOrdinals` is the primitive that resolves this. It is a record of per-environment **activation ordinals** (`config/types.scala:27-48`): each new or changed deterministic behavior is gated so that history strictly below its ordinal re-derives on the OLD path (byte-identical to what was signed), while at and after the ordinal the new behavior applies. The decision is always `ordinal >= gate`, **never** a branch on `AppEnvironment`. The values are compile-time HOCON literals packaged into the assembly jar, and peers only connect to peers running a matching jar hash, so the jar hash plus the environment is the determinism fence.

## Mechanism

`FieldsAddedOrdinals` is a flat record of maps, one per gated behavior (`config/types.scala:27-48`):

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
  dustSweeps: Map[AppEnvironment, SortedMap[SnapshotOrdinal, DustSweep]] = Map.empty
)
```

Each gate is loaded from the `fields-added-ordinals` HOCON block (`application.conf:210-293`). Resolution is always the same shape: pick the entry for the running environment, then compare the snapshot ordinal against it. Two value conventions appear:

- A **threshold gate** (`Map[AppEnvironment, SnapshotOrdinal]`): the behavior is gated by `ordinal >= gate`. Absent-environment resolution falls to `SnapshotOrdinal.MinValue` (e.g. `scFeeBalanceFromContext.getOrElse(environment, SnapshotOrdinal.MinValue)` at `GlobalSnapshotConsensus.scala:150` and `SharedServices.scala:193`), which means "always on" for the new path. A high placeholder such as `9999999` is the inverse: it keeps the OLD path live until the placeholder is replaced with the real launch ordinal.
- An **exact-key gate** (`dustSweeps: Map[AppEnvironment, SortedMap[SnapshotOrdinal, DustSweep]]`): the behavior fires only at exactly the keyed ordinal (`dustSweeps.get(env).flatMap(_.get(ordinal))`), once, and never replays.

Per-environment activation ordinals differ because the same fix crosses different points of different chains. The behavior itself is identical code on every network; only WHEN it activates is per-environment. Examples from `application.conf:210-293`:

| Gate | mainnet | testnet | integrationnet | dev |
|------|---------|---------|----------------|-----|
| `tessellation-3-migration` | 4409045 | 2497000 | 3330000 | 0 |
| `fixing-allow-spend-and-token-lock-validation` | 5058096 | 9999999 | 9999999 | 0 |
| `set-sum-fix` | 9999999 | 9999999 | 9999999 | 0 |
| `sc-fee-balance-from-context` | 9999999 | 0 | 0 | 0 |
| `dust-sweeps` | (none) | {3154700} | (none) | (none) |

A `9999999` entry is a not-yet-activated placeholder: the chain has not reached it, so the OLD path is still live on that environment. A `0` entry means the new path is active from genesis on that environment. An absent environment (no map entry) means the behavior never activates there.

## The no-env-gating principle

The load-bearing rule:

> New consensus functionality is ALWAYS present in the code for every network. You gate WHEN it activates by ordinal, never branch consensus behavior on `AppEnvironment`. Per-environment differences are a deployment concern: which config values are set, and which ordinal gates are armed per deploy. The jar hash is the cluster fence.

Concretely, the read sites compare `ordinal >= gate`, never `if (environment == Mainnet)`. See `GlobalSnapshotStateChannelEventsProcessor.scala:325`:

```scala
if (snapshotOrdinal >= scFeeBalanceFromContextOrdinal)
  lastGlobalSnapshotInfo.balances.getOrElse(feeAddress, Balance.empty).pure[F]   // new path
else
  mptStore.getBalance(feeAddress).map(_.getOrElse(Balance.empty))               // old path
```

A consensus knob that is testnet-only in HOCON (so mainnet silently falls to a no-op default) **violates this principle**, because the per-environment behavior difference then lives in a runtime branch rather than in consensus-agreed state, and a future contributor cannot see, from the gate, that mainnet behaves differently. The correct way to express a per-environment consensus difference is a `Map[AppEnvironment, T]` config value that is resolved ONCE at the construction site and folded into `deterministicConfigHash` (see below). That way a divergent operator value is refused at Facility-handshake time rather than producing a silent fork.

The same discipline applies to env-keyed consensus knobs that are not ordinal gates (for example `coreCommitteeSize`, `quorumShrinkActivationViews`, `rewardRotationEpochRounds`): they are resolved per environment at one construction point and folded into `deterministicConfigHash` (`types.scala:1019-1043`), so the per-environment value is part of the consensus contract, not a runtime branch.

## Three fences operators must not conflate

| Mechanism | What it is | Replay-relevant? | Failure mode on divergence |
|-----------|------------|------------------|----------------------------|
| **FieldsAddedOrdinals** | per-env activation ordinals for deterministic behavior changes | **Yes** | a mismatched ordinal changes artifact bytes at the boundary -> fork |
| **deterministicConfigHash** | a hash of dozens of consensus knobs (~48 folded fields) concatenated into one string (`types.scala:950-1044`, folded string ends at `:1043`) | No (it is a config FENCE, not signed into history) | a divergent value handshake-rejects the peer connection / fails the Facility `consensusConfigHash` check; it does NOT change replayed bytes |
| **consensusSchemaVersion** | a single integer wire-version fence (`types.scala:830`, currently `33`), folded INTO `deterministicConfigHash` | No | a divergent value fences out mixed-wire-version peers at handshake; it is not signed into the snapshot artifact |

The distinction that matters for operators: `FieldsAddedOrdinals` is the ONLY one of the three that changes replayed history bytes. `deterministicConfigHash` and `consensusSchemaVersion` are connect-time fences that prevent mismatched nodes from joining at all; they are replay-irrelevant. Setting a wrong ordinal does not get caught at handshake. It forks the chain when the gate is crossed. That is why ordinal gates are the highest-stakes value to get right at deploy.

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

`scFeeBalanceFromContext` (`config/types.scala:38-42`) is a threshold gate over the balance source used by the state-channel fee-affordability check. At and after the gate the check reads the metagraph owner's balance from the deterministic `accept()` context (`lastGlobalSnapshotInfo.balances`); below it from the pre-fix `mptStore.getBalance` path, so already-signed history re-derives byte-identically (`GlobalSnapshotStateChannelEventsProcessor.scala:325`). testnet is `0` (the fix is already live there); the mainnet entry is the `9999999` placeholder, which keeps the OLD path until it is set to the coordinated launch ordinal (`application.conf:271-280`).

## Operator checklist

- Ordinal gates are **consensus-critical** and must match cluster-wide. They live in `application.conf` and are packaged into the assembly jar (compile-time literals), so changing one is itself a coordinated jar redeploy.
- Before launch, replace every mainnet placeholder with the real coordinated launch ordinal:
  - `sc-fee-balance-from-context.mainnet` (currently `9999999`, `application.conf:275-280`). Leaving it unset silently keeps the pre-fix `mptStore` balance-source path.
  - `dust-sweeps` has no mainnet entry yet (`application.conf:286-292`). If a mainnet sweep is intended, add one.
- For the dust sweep specifically, FINALIZE the ordinal right before deploy: it must be an ordinal the chain reaches AFTER the deflating jar is live cluster-wide. A too-early crossing on the old jar misses the sweep until a rollback re-crosses it (`application.conf:281-285`). Bump it up if the chain nears it before the coordinated cold restart completes.
- These gates do NOT participate in `deterministicConfigHash`, so a wrong ordinal is NOT caught at handshake. It forks the chain when the gate is crossed. Verify them by inspection before deploy.

## Key code references

| Concern | Location |
|---------|----------|
| `FieldsAddedOrdinals` record | `config/types.scala:27-48` |
| `DustSweep` config | `config/types.scala:50-61` |
| HOCON block | `application.conf:210-293` |
| Dust sweep transform | `GlobalSnapshotDustSweep.scala:16-150` |
| Dust sweep acceptance wiring + `syncFull` | `GlobalSnapshotAcceptanceManager.scala:1086-1160` |
| `scFeeBalanceFromContext` read site | `GlobalSnapshotStateChannelEventsProcessor.scala:325` |
| `scFeeBalanceFromContext` resolution | `GlobalSnapshotConsensus.scala:150`, `SharedServices.scala:193` |
| `deterministicConfigHash` folded string | `config/types.scala:950-1044` (folded string ends `:1043`) |
| `consensusSchemaVersion` | `config/types.scala:830` |
