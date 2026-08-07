# 26. MPT proof system

Date: 2026-06-30

## Status

Accepted

## Context

A single-root state commitment (ADR-0024) is only useful if a peer or light client can prove a specific `(key, value)` membership without holding the entire trie, and can do so without ambiguity about which node type produced a given hash.

## Decision

- **Type-prefixed commitment hashing.** Hash over typed commitment structures with distinct byte prefixes -- Leaf `0x00`, Branch `0x01`, Extension `0x02` -- so the node type is bound into the hash and two different node structures cannot collide.
- **Four inclusion-proof types:** single; batch (deduplicates shared witness nodes, ~40% size reduction on related keys); range; and prefix. All verify against the published root.

## Consequences

- Light-client and peer verification of arbitrary state; bandwidth-efficient multi-key proofs.
- **Cost:** the proof code is security-critical -- an error in the prefix scheme or witness handling breaks the binding property. The concern is localized to the proof subsystem and does not affect the core trie or state commitment.

Mechanism reference: `docs/mpt/proof-system.md`, `docs/mpt/api-reference.md`.
