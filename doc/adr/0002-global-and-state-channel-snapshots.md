# 2. Global and StateChannel snapshots

Date: 2022-01-05

## Status

Proposed

## Context

Global snapshots and StateChannel snapshots should be defined with their
responsibilities.

## Decision

### StateChannel snapshots

L0 Cell can be identified as a proxy point between Layer 1 and Layer 0. Each
state channel will have its own L0 Cell registered in the Layer 0. One of the
responsibilities of the L0 Cell is to create a StateChannel snapshot. It can be
performed either by proxying the snapshot received from Layer 1 or by
periodically creating StateChannel snapshots inside the L0 Cell. Overall, the
StateChannel snapshot needs to be returned as an output of an L0 Cell so then it
will be aggregated by Layer 0 and proposed to the Global snapshot.

### Global snapshots

Global snapshots are being created on Layer 0. They may contain both DAG blocks and
StateChannel snapshots. Trigger for the global snapshot will be described by its
own ADR. Global snapshots that passed majority algorithm can be considered final
and immutable aka. source of truth.

## Consequences

- Layer 1 needs a redownload algorithm when misaligned with the Global snapshot
- Majority Global snapshots are the source of truth
- Each StateChannel needs to register its own Layer 0 Cell.
