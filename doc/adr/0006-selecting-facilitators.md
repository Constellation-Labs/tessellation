# 6. Selecting facilitators

Date: 2022-01-17

## Status

Accepted

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
