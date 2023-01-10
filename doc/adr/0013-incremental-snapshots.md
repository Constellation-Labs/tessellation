# 13. Incremental snapshots

Date: 2023-01-10

## Status

Accepted

## Context

[ADR#10](./0010-reducing-snapshot-size.md) provides valid context and the solution proposed works well for $DAG, but having state channels dumping state to GlobalSnapshot, full snapshots will stay suboptimal.

## Decision

We decide to change full snapshots into incremental snapshots.
[tbd]

## Consequences

[tbd]

### Aggregated data

Data that are currently aggregated within the GlobalSnapshot and needs to be removed:
- last state-channel snapshot hashes,
- last transaction references,
- balances.

### Verifying alignment

Full GlobalSnapshot *is* the current state of the network, hence it is enough to compare local state to last GlobalSnapshot. Since snapshots would be incremental only, local state needs to be calculated and cannot be derived or compared as is. GlobalSnapshot needs to store a hash of the current local state to compare it with calculated state on the nodes. Hash should be stored separately per aggregated type.

#### Merkle tree

- [tbd] State hash
- [tbd] Unbalanced merkle tree
- [tbd] Arbitrarily structured trees
- [tbd] Verifying partial data

