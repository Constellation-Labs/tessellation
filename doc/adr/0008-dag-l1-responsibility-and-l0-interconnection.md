# 8. DAG L1 responsibility and L0 interconnection

Date: 2022-01-05

## Status

Accepted

## Context

DAG L1 responsibility and communication between DAG L1 and L0 needs to be
specified.

## Decision

DAG Layer 1 returns DAG blocks as an output and it doesn't create snapshots.
DAG Layer 1 sends DAG blocks to the Layer 0 so then the Layer 0 can create a Global
snapshot.

Cross-chain swaps will take place on the Layer 0, so then Layer 0 can create
additional DAG transactions and blocks.

DAG Layer 1 will subscribe to Layer 0, to pull Global snapshots and other data like selected facilitators. If there is a
difference between accepted DAG blocks and DAG blocks from Global snapshot, DAG
Layer 1 will apply missing blocks. In the case of conflicting transactions,
redownload algorithm will be triggered, to reapply data and converge with the Global snapshot.

## Consequences
Communication between L1 and L0 will be bi-directional in a Push/Pull way.
