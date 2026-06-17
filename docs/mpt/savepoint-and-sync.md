# MPT Savepoint, Restore, and Content-Aware Sync

This document describes the MPT safety mechanisms that keep the shared Merkle Patricia Trie (MPT) in agreement with the consensus-signed state proof across abandoned rounds, recovery downloads, and the one-shot dust sweep. The consensus proposal path mutates a single shared `MptStore` in place, so a failed or abandoned round can leave partial mutations behind. Three mechanisms guard against that partial state ever producing a divergent root: `savepoint`/`restore` (undo failed mutations), `syncFullIfNeeded` with a content-aware skip (never trust the "already synced" tag without verifying the produced root against the signed `stateProof`), and `syncFull` (one-shot rebuild from a full state map). Because the trie root is a pure function of the `(key -> value)` set, a full rebuild yields the identical canonical root the incremental path would have produced, which is what lets the dust sweep rebuild the trie in one shot without breaking consensus. An opt-in diagnostic (`CL_MPT_VERIFY_INCREMENTAL`) independently rebuilds the full root and logs any drift.

The source of truth is `modules/shared/src/main/scala/io/constellationnetwork/schema/mpt/MptStore.scala`.

## 1. Insertion-Order-Independent Root

The MPT root depends only on the set of `(key -> value)` entries, not on the order in which they were inserted. Branch children are sorted by nibble value at every traversal and digest computation, so two stores that hold the identical entry set produce the identical root regardless of insertion history.

See the `sortBy(_._1)` over branch children in `MerklePatriciaTrie.collectLeafNodes` / `collectLeafNodesWithPaths` (`security/mpt/MerklePatriciaTrie.scala:51`, `:71`). Each `MerklePatriciaNode` carries a digest computed at construction, and a branch's child paths are sorted by nibble value before that digest is computed (`security/mpt/MerklePatriciaNode.scala:20`, `:133`), so the root is a deterministic function of the entry set alone. The trie's `rootHash` then just reads the root node's precomputed digest (`security/mpt/MerklePatriciaTrie.scala:16-22`).

The practical consequence: if two nodes compute different roots, it is **not** an insertion-order artifact. It means a genuinely different key set, a different value-byte serialization, or stale state left behind by a failed mutation. This is the invariant the rest of this document protects.

## 2. savepoint / restore

`MptStore.savepoint` captures a full snapshot of the store's mutable state and returns an `MptStoreSavepoint` whose `restore` rolls the store back to that exact state (`MptStore.scala:19-24`, `:44-47`, `:325-335`).

What is captured:

- The **producer state** via `producer.savepoint`. For both the in-memory and filesystem producers this is the in-memory `state`, the built `trie`, the `pendingInserts` / `pendingRemoves` buffers, the `rootHashCache`, and the `lastBuiltOrdinal` (`producer/InMemoryMerklePatriciaProducer.scala:225-242`, `producer/FileSystemMerklePatriciaProducer.scala:344-361`).
- The store's **`lastSyncedOrdinal`** tag (`MptStore.scala:328-329`), so that a restore also reverts the "which ordinal are we synced at" marker, not just the trie contents.

`restore` resets the captured producer savepoint and then re-sets `lastSyncedOrdinalRef` to the saved value (`MptStore.scala:332-333`).

Both `savepoint` and `restore` run under the store's `mutationLock` (`MptStore.scala:78`, `:326`, `:333`). That semaphore serializes the heavy mutation methods (`syncFull` / `sync` / `update` / `deleteAbove`) and the multi-`Ref` savepoint capture so concurrent callers (the FSM proposal path, the download path, state-channel sync) cannot tear the producer's internal state mid-operation (`MptStore.scala:58-68`).

The savepoint captures in-memory producer state. It is the undo primitive for a single round's mutations; it is not a substitute for the on-disk persisted history that `deleteAbove` / cutoff manage.

## 3. syncFull(ordinal)

`syncFull(newState, ordinal)` rebuilds the trie in one shot from a complete state map (`MptStore.scala:225-240`). It clears the store, inserts the full entry set, kicks off an async persist, builds the trie for the ordinal, and tags `lastSyncedOrdinal = ordinal`. An empty `newState` clears the store and tags the ordinal (`MptStore.scala:227-229`).

Because the root is insertion-order-independent (section 1), `syncFull` produces the **identical canonical root** that the incremental path (`sync` / `syncFromStateChanges`) would produce for the same entry set. This equivalence is what makes the one-shot rebuild safe to use mid-stream without forking the cluster.

## 4. syncFullIfNeeded with content-aware skip

`syncFullIfNeeded(newState, ordinal, expectedRoot)` is the guarded sync used before computing a proposal (`MptStore.scala:242-288`). `newState` is by-name (`=> F[Map[K, V]]`) so the potentially expensive full-state materialization is only forced when a sync actually runs.

It first does an atomic `lastSyncedOrdinalRef.modify` to decide `needsSync = lastOrdinal.forall(_ =!= ordinal)`, marking the ordinal immediately so two concurrent callers cannot both see `needsSync = true` (`MptStore.scala:249-256`).

- If `needsSync` is true, it forces `newState.flatMap(syncFull(_, ordinal))` (`MptStore.scala:262`).
- If `needsSync` is false (the ordinal tag already matches), the **content-aware skip** runs (`MptStore.scala:264-287`):
  - With `expectedRoot = None`, it trusts the tag and logs a plain skip (`MptStore.scala:270-271`).
  - With `expectedRoot = Some(expected)`, it does **not** trust the tag blindly. It calls `build(ordinal)` (not `getCurrentRootHash`) so that any pending inserts/removes are applied before the root is read, then compares the produced root to `expected` (`MptStore.scala:272-286`). On a match it logs a verified skip. On a mismatch (including a `Left` build) it logs a warning and forces `newState.flatMap(syncFull(_, ordinal))` to avoid emitting a divergent root.

The reason the tag alone is not trusted: an abandoned-round mutation or a `restore` can leave the in-memory entry set stale while the `lastSyncedOrdinal` tag still reads as synced. A pure ordinal check would then let a proposal/proof be built off divergent state. Verifying the produced root against the signed `stateProof` root closes that gap (`MptStore.scala:264-268`).

## 5. Wiring into the proposal path

The proposal-building path in `GlobalSnapshotConsensusStateAdvancer` ties these together (`dag-l0/.../snapshot/GlobalSnapshotConsensusStateAdvancer.scala`).

Order of operations before `createArtifact`:

1. **Restore first.** If a savepoint from an abandoned round at the **same** ordinal (`state.key`) exists in `proposalSavepointRef`, restore it before anything else; a savepoint keyed to a different ordinal (e.g. after a recovery download) is discarded, not restored (`GlobalSnapshotConsensusStateAdvancer.scala:1085-1113`). Restoring after the sync would replace a forced resync with the abandoned round's stale producer state (`:1086-1087`). A restore logs `Event.MptSavepointRestored` and increments `dag_consensus_mpt_savepoint_restored_total`; a wrong-key discard logs `Event.MptSavepointDiscardedWrongKey`.
2. **Content-guarded sync.** Call `mptStore.syncFullIfNeeded` with the `lastOutcome`'s state entries, the `lastOutcome.key`, and `lastOutcome.finished.signedMajorityArtifact.value.stateProof.mptRoot` as `expectedRoot` (`:1121-1125`). This makes the no-op path a no-op only when the producer's current root reproduces the `lastOutcome`'s signed state-proof root; on mismatch it forces a full resync so an abandoned-round mutation can never leave the MPT stale under `createArtifact` (`:1115-1120`).
3. **Fresh savepoint.** Take a new savepoint and store it under the current key in `proposalSavepointRef` (`:1126-1128`), so the next abandoned-round retry at the same ordinal can restore it.

The slow follower/validation paths take the same precaution: a savepoint is taken before `validateArtifact` / `createContext` mutates the shared store and is restored on validation failure or on any unexpected exception (`GlobalSnapshotConsensusStateAdvancer.scala:76-91`, `:1373`, `:2313-2375`, `:2474-2479`). On a successful round the proposal savepoint is discarded so it is not restored against the next ordinal (`:2985`). The acceptance-manager scaladoc states the same obligation: on a stateProof-validation failure the caller MUST restore from a savepoint to prevent partial state leaking into future rounds (`node-shared/.../snapshot/managers/global/GlobalSnapshotAcceptanceManager.scala:113-114`).

## 6. syncFull at the dust-sweep ordinal

The acceptance pipeline branches its MPT update on whether the ordinal-gated GSI dust sweep ran (`GlobalSnapshotAcceptanceManager.scala:1154-1160`):

```scala
_ <-
  if (didSweep) sweptGsi.allStateEntries[F].flatMap(mptStore.syncFull[Json](_, ordinal))
  else mptStore.syncFromStateChanges(stateChangesAccumulator, ordinal)
```

At the sweep ordinal the entry set drops from hundreds of thousands of addresses to a few hundred, so a one-shot `syncFull` from the swept state is sub-second versus streaming hundreds of thousands of incremental deletions. Because the root is a pure function of the entry set (section 1), the one-shot rebuild yields the identical canonical root the incremental path would for the same swept entry set, so consensus still agrees and the producer resumes correct incremental syncs from the pruned base afterward (`:1154-1157`). `buildProof` then derives the `stateProof` from the same swept GSI (`:1161`).

## 7. Opt-in diagnostic: CL_MPT_VERIFY_INCREMENTAL

An opt-in, log-only verifier rebuilds the full MPT root from the swept GSI and compares it to the incremental store root that `buildProof` just produced (`GlobalSnapshotAcceptanceManager.scala:1163-1186`).

- **Gate.** It runs only on the incremental path (`!didSweep`) and only when the environment variable `CL_MPT_VERIFY_INCREMENTAL` is set to `true` (case-insensitive): `sys.env.get("CL_MPT_VERIFY_INCREMENTAL").exists(_.equalsIgnoreCase("true"))` (`:1186`). It is off by default and read from the process environment (there is no HOCON binding).
- **Lazy.** The whole block is guarded by `whenA`, so it costs nothing when the flag is off.
- **Standalone rebuild.** It calls `GlobalSnapshotInfo.mptStateProof(sweptGsi)` to build an independent full root that does **not** touch the shared store (`:1169-1172`).
- **Log-only.** On `stateProof.mptRoot =!= fullRebuildRoot` it logs `[MPT.VERIFY] ordinal=... INCREMENTAL DRIFT detected: incrementalRoot=... != fullRebuildRoot=...` (`:1173-1179`). A drift means `syncFromStateChanges` diverged from the canonical entry set: the live, in-memory analogue of the historical "MptStore carries wrong state incrementally" class of divergence, caught at the ordinal where it is introduced before it can propagate.
- **Never affects acceptance.** Any rebuild or serialization failure is swallowed with a warning so a node with the flag set cannot fail or diverge from nodes without it (`:1181-1185`).

Because it is purely diagnostic, the verifier is safe to enable on a node under investigation: it only reads and logs, and never changes which artifact is accepted.

## See also

- `docs/mpt/integration.md` for how the MPT integrates with the snapshot/state-proof system.
- `docs/mpt/proof-system.md` and `docs/mpt/data-structures.md` for the trie/node/proof types.
