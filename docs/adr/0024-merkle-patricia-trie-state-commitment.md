# 24. Merkle Patricia Trie for global state commitment

Date: 2026-06-30

## Status

Accepted

Relates to ADR-0010 (Reducing global snapshot size).

## Context

Global state -- per-address balance, last transaction reference, last state-channel snapshot hash -- was committed via a per-field hash scheme layered on the `GlobalSnapshotInfo` delta-aggregation of ADR-0010. That approach offered no efficient `O(log n)` inclusion proof, no way for a light client to verify an arbitrary state claim, and its per-field structure contributed to the v4.0.0 state-root divergence that broke the release.

## Decision

Commit the entire global state under a single **Merkle Patricia Trie** root. Key design choices:

- **Immutable pre-computed digests.** Node digests are computed at construction and stored on the node, so there is no runtime recomputation or cache-invalidation tracking; digests are `O(1)` and concurrent reads are safe.
- **Insertion-order-independent root.** Branch children are sorted by nibble value at traversal and digest computation, so the root is a pure function of the `(key, value)` set. Incremental updates produce a byte-identical root to a full rebuild -- foundational for replay determinism (ADR-0025) and savepoint/restore.
- **Compact nibble paths.** `CompactNibblePath` packs two nibbles per byte (~20-40x memory reduction versus `Seq[Nibble]` for 32-byte keys).
- **Namespaced keys.** `GlobalStateKey` is structured `network / field-id / contract / user`, enabling state partitioning, range queries, and prefix proofs.
- **Producer abstraction.** Construction is factored into `MerklePatriciaProducer` (stateless one-shot), `StatefulMerklePatriciaProducer` (incremental in-memory), and `StatefulWithPersistenceMerklePatriciaProducer` (filesystem-backed, with per-ordinal root caching), decoupling construction strategy from consumption.

Currency state proofs remain in the legacy format; the *global* proof migrates via an ordinal-gated selector (ADR-0025).

## Consequences

- Single-root commitment with efficient proofs (ADR-0026); the order-independent root is what makes deterministic incremental production and the one-shot GSI dust sweep (ADR-0025) safe.
- Light-client verifiability of arbitrary state.
- **Cost:** a new subsystem and a new canonical-serialization surface; hashing must be exact (type-prefixed, ADR-0026) or proofs break; it interacts tightly with the replay gating in ADR-0025.
- Relates to / partially supersedes ADR-0010 (the delta-aggregation view is overtaken by the trie model).

Mechanism reference: `docs/mpt/architecture.md`, `docs/mpt/data-structures.md`.
