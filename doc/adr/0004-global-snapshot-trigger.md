# 4. Global snapshot trigger

Date: 2022-01-17

## Status

Accepted

## Context

Global snapshot must have a deterministic trigger so then snapshots created on
different nodes should converge (contains the same data) and pass the majority algorithm.

As described in [ADR#7](./0007-global-and-state-channel-snapshots.md), Global
snapshot contains both DAG blocks and StateChannel snapshots.

## Decision

There are 2 ways of triggering the Global Snapshot: "Tips trigger" and "Periodic trigger".

### Tips trigger
L1 nodes send tips data along with the blocks. L0 nodes aggregate the tips and trigger the Global Snapshot
when tips reached some interval (will be hardcoded). That trigger creates "non-empty" Global Snapshot
and should increment `height` and set `subHeight` to `0`.

### Periodic trigger
If there are no new blocks sent from L1 then L0 nodes - periodically try to create "empty" Global Snapshot.
Each new "empty" Global Snapshot should increment `subHeight` until "non-empty" snapshot is created.

### Ordinal
Each snapshot has unique ordinal number. Ordinal number is assigned to new Global Snapshot
by incrementing the value od previous Global Snapshot. It happens always no matter which trigger has been chosen.

## Consequences

- Global Snapshots created by different triggers should increment height in a different way
- Ordinal number should be introduced in Global Snapshots to make it compatible with Rosetta
