# 6. Selecting facilitators

Date: 2022-01-17

> **Superseded by ADR-0017 (leader-based consensus) and ADR-0036 (single frozen voting set).** The model described below -- L0 selecting facilitators by looking at the L1 active peer list, storing them in the Global Snapshot for L1 to fetch -- no longer reflects the implementation. Global L0 membership is now one frozen voting set per round with round-robin leaders and an explicit certified successor rule; see [ADR-0036](0036-single-frozen-voting-set.md). The body below is retained as historical context.

## Status

Superseded

## Context

Facilitators of L1 should be selected by L0 layer.

## Decision

L0 nodes can select next facilitators by looking at the active peer list from L1.
These facilitators are put to Global Snapshot so L1 can fetch it and run the consensus.
L0 nodes will validate signatures of previously selected facilitators when blocks are received from l1.
If block misses the chance to be proposed within the time window and new facilitators are selected, then block data
should be re-enqueued into mempool.

## Consequences

- Next facilitators should be stored in Global Snapshot
