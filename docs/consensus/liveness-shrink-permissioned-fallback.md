# Liveness Shrink: permissioned-fallback committee reduction (retired)

**Status: Retired (superseded by [ADR-0036](../adr/0036-single-frozen-voting-set.md)).** This proposal was never built, and the shipped alternative it was compared with (quorum shrink) has also been removed. Global L0 now never shrinks the quorum; a stalled cluster halts and is recovered with the permissioned recovery-seed procedure.

## Historical content

Everything below describes a removed mechanism and is kept for context only. Do not cite it as current behavior.

**Status:** Removed (ADR-0035 / #1627, 2026-10-10). Already SUPERSEDED and never
implemented; retained as a stub so existing links (including ADR-0018's list of rejected
alternatives) resolve.

This was a 2026-05-12 proposal to unwedge a committee whose quorum-threshold subset was
persistently down by certifying a reduction to a per-environment permissioned fallback
set (`LivenessShrink` vote and certificate). It was never built. The wedge class was
instead addressed by the v33 quorum-denominator shrink rung
([quorum-shrink.md](quorum-shrink.md)), which was itself removed with the pre-v35 Global
L0 engine. The ADR-0021 frozen-committee supermajority survives as the only quorum,
`FinalityQuorum.of` over `roundStartFacilitators`
([ADR-0036](../adr/0036-single-frozen-voting-set.md)).

See [ADR-0035](../adr/0035-v35-to-v41-mainnet-migration.md),
[ADR-0018](../adr/0018-supermajority-quorum-and-between-round-eviction.md) and the Amendment
section of [ADR-0021](../adr/0021-quorum-shrink-and-finality-floor.md). The full former text
is in git history, for example
`git show origin/develop:docs/consensus/liveness-shrink-permissioned-fallback.md`.
