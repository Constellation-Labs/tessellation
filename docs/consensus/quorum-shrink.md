# Quorum-denominator shrink (v33 liveness rung)

**Status:** Removed (ADR-0035 / #1627, 2026-10-10). Retained as a stub so existing links
resolve.

`QuorumDenominatorShrink` (consensusSchemaVersion 33) was a liveness rung for the pre-v35
Global L0 engine. After `quorum-shrink-activation-views` view intervals of wall-clock
silence at a stuck key it deterministically lowered the quorum denominator used by
VCC/TC/eviction assembly and stall feasibility, without changing the persisted committee.
It was enabled only on testnet (mainnet, integrationnet and dev configured `0`). It was
removed together with the pre-v35 engine, the `quorum-shrink-activation-views` knob and the
`dag_consensus_quorum_shrink_*` metrics. Certified consensus (ADR-0032) never lowers the
quorum denominator at a stuck key: VCC and TC are frozen-Core quorum certificates.

The ADR-0021 finality floor survives. `FinalityQuorum.scala`
(`modules/node-shared/.../infrastructure/consensus/state/FinalityQuorum.scala`) keeps the
Core liveness quorum and the frozen-committee finality floor, and Global L0 sets
`clusterFloorActive = true` in every round, including bootstrap
(`GlobalSnapshotConsensusStateAdvancer.scala:388`).

See [ADR-0035](../adr/0035-v35-to-v41-mainnet-migration.md) and the Amendment section of
[ADR-0021](../adr/0021-quorum-shrink-and-finality-floor.md). The full former text is in git
history, for example `git show origin/develop:docs/consensus/quorum-shrink.md` (before
#1627 merges) or `git log -- docs/consensus/quorum-shrink.md`.
