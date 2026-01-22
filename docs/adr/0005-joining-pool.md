# 5. Joining pool

Date: 2022-01-17

## Status

Proposed

## Context

Joining pool is a mechanism of shifting active nodes in the cluster.

## Decision

Joining pool is selected by L0 nodes, so same pool is available on both L0 and L1.
Next peers are selected as a diff of previous pool.

## Consequences

- L0 needs to know about L1 nodes
- Global Snapshot needs to contain information about active peer list
