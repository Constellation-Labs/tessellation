# 25. MPT replay-safety and state-root divergence prevention

Date: 2026-06-30

## Status

Accepted

## Context

v4.0.0 was broken by an MPT state-root divergence. Two hard requirements emerged:

1. Replaying historical snapshots must re-derive the state root byte-identically on the code path *as it existed at that ordinal*. Environment-conditional consensus branches (`if (env == Mainnet) ...`) are themselves a replay hazard, because the branch taken depends on deploy config rather than on the data.
2. Abandoned-round mutations and `restore` could leave the in-memory trie inconsistent with the persisted history (the `lastSyncedOrdinal` tag said "synced" while the entry set was stale), so a future proposal emitted a divergent root.

## Decision

A bundle of ordinal-gated, content-aware mechanisms:

1. **`FieldsAddedOrdinals` gating.** Every new or modified deterministic behavior is gated by a per-environment activation ordinal. Code reads `ordinal >= gate`, NEVER `if (env == ...)`. Each gate is a `Map[AppEnvironment, SnapshotOrdinal]` with fail-closed defaults, pinned to real cutovers (e.g. testnet `sc-fee-balance-from-context = 3101393`, the exact v4.0.0 -> alpha.0 boundary).
2. **`StateProofSelector`.** Ordinals `<= last-legacy-state-proof-ordinal` keep the legacy per-field hashes; above it, the MPT single root. Currency proofs always stay legacy.
3. **Savepoint / restore.** `MptStore.savepoint` captures producer state plus `lastSyncedOrdinal` before mutation-prone operations (proposals, validation); `restore` rolls both back on failure or abandonment.
4. **Content-aware `syncFullIfNeeded`.** When an `expectedRoot` is provided, rebuild the trie and compare to the signed state-proof root; force a full `syncFull` on mismatch rather than trusting the ordinal tag alone.
5. **GSI dust sweep.** An exact-ordinal-gated (`Map[AppEnvironment, SortedMap[SnapshotOrdinal, DustSweep]]`) one-shot state deflation, rebuilding the MPT via `syncFull` from the swept state -- safe precisely because the root is insertion-order-independent (ADR-0024). Collapsed bloated testnet state from ~80MB to ~1MB.
6. **Sub-trie roots.** Ordinal-gated per-`GlobalStateFieldId` roots in `GlobalSnapshotStateProof`, so when the overall root diverges the per-field roots localize which field's trie diverged.
7. **`CL_MPT_VERIFY_INCREMENTAL` diagnostic.** Opt-in: independently rebuilds the root and logs a mismatch, but never fails acceptance.

## Consequences

- Byte-identical replay across node versions; divergence is detected and prevented at the acceptance path rather than discovered ordinals later.
- The dust sweep cleans state without forking.
- **Cost:** every deterministic behavior change now requires an ordinal gate plus a per-environment pin -- real operational discipline (`docs/operations/fields-added-ordinals.md`). A mis-set gate ordinal forks the chain. The config surface is large.
- Relates to / supersedes parts of ADR-0010 and ADR-0014. Built on ADR-0016 (determinism) and ADR-0024 (order-independent root).

The per-environment gate values are tabulated in `docs/operations/fields-added-ordinals.md` (e.g. testnet `sc-fee-balance-from-context = 3101393`) and configured in the `fields-added-ordinals` block of `application.conf`.

Mechanism reference: `docs/mpt/savepoint-and-sync.md`, `docs/operations/fields-added-ordinals.md`.
