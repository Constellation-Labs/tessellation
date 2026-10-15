# Liveness Shrink: permissioned-fallback committee reduction

**Status:** Removed (ADR-0035 / #1627, 2026-10-10). Already SUPERSEDED and never
implemented; retained as a stub so existing links (including ADR-0018's list of rejected
alternatives) resolve.

This was a 2026-05-12 proposal to unwedge a committee whose quorum-threshold subset was
persistently down by certifying a reduction to a per-environment permissioned fallback
set (`LivenessShrink` vote and certificate). It was never built. The wedge class was
instead addressed by the v33 quorum-denominator shrink rung
([quorum-shrink.md](quorum-shrink.md)), which was itself removed with the pre-v35 Global
L0 engine. The ADR-0021 finality floor survives in `FinalityQuorum.scala`
(`modules/node-shared/.../infrastructure/consensus/state/FinalityQuorum.scala`), and Global
L0 sets `clusterFloorActive = true` in every round
(`GlobalSnapshotConsensusStateAdvancer.scala:388`).

See [ADR-0035](../adr/0035-v35-to-v41-mainnet-migration.md),
[ADR-0018](../adr/0018-supermajority-quorum-and-between-round-eviction.md) and the Amendment
section of [ADR-0021](../adr/0021-quorum-shrink-and-finality-floor.md). The full former text
is in git history, for example
`git show origin/develop:docs/consensus/liveness-shrink-permissioned-fallback.md`.
