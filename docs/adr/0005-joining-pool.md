# 5. Joining pool

Date: 2022-01-17

> **Superseded.** This was never implemented as written (Status stayed Proposed), and the concern is now solved differently. Joining is a multi-round candidate-registration pipeline rather than an L0-selected pool diff: a peer enters `Observing`, other nodes' `peerRegistrationStream` query its `/consensus/registration` endpoint, the candidate appears in the leader's `Facility`, and `CommitteeBuilder` admits new joiners into Tier 1 (not the Core liveness quorum) until their `peerQuality` proves them above the ratio bar. The active set is recomputed every round from `eligibleFacilitators` + `peerQuality` / `peerTiers`; what the snapshot persists is `peerHistory` (`ConsensusOperationalState`), not an active-peer list. See [docs/consensus/README.md](../consensus/README.md) section 9 ("Candidate Registration"). The body below is retained as historical context.

## Status

Superseded

## Context

Joining pool is a mechanism of shifting active nodes in the cluster.

## Decision

Joining pool is selected by L0 nodes, so same pool is available on both L0 and L1.
Next peers are selected as a diff of previous pool.

## Consequences

- L0 needs to know about L1 nodes
- Global Snapshot needs to contain information about active peer list
