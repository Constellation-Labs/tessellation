# Quorum-denominator shrink (v33 liveness rung) (retired)

**Status: Retired (superseded by [ADR-0036](../adr/0036-single-frozen-voting-set.md)).** `QuorumDenominatorShrink` and `quorum-shrink-activation-views` are deleted. Every gate uses one quorum over the frozen round committee, and under more than `f` failures the round halts instead of lowering the denominator; recovery uses the permissioned recovery-seed procedure. Current behavior: [README sec 5](README.md#5-consensus-round-phases).

## Historical content

Everything below describes a removed mechanism and is kept for context only. Do not cite it as current behavior.

**Status:** Removed (ADR-0035 / #1627, 2026-10-10). Retained as a stub so existing links
resolve.

`QuorumDenominatorShrink` (consensusSchemaVersion 33) was a liveness rung for the pre-v35
Global L0 engine. After `quorum-shrink-activation-views` view intervals of wall-clock
silence at a stuck key it deterministically lowered the quorum denominator used by
VCC/TC/eviction assembly and stall feasibility, without changing the persisted committee.
It was enabled only on testnet (mainnet, integrationnet and dev configured `0`). It was
removed together with the pre-v35 engine, the `quorum-shrink-activation-views` knob and the
`dag_consensus_quorum_shrink_*` metrics. Certified consensus (ADR-0032) never lowers the
quorum denominator at a stuck key: VCC and TC are committee quorum certificates.

The ADR-0021 frozen-committee supermajority survives as the only quorum:
`FinalityQuorum.of` (`modules/node-shared/.../infrastructure/consensus/state/FinalityQuorum.scala`)
over the frozen `roundStartFacilitators`, in every round including bootstrap
([ADR-0036](../adr/0036-single-frozen-voting-set.md)).

See [ADR-0035](../adr/0035-v35-to-v41-mainnet-migration.md), [ADR-0036](../adr/0036-single-frozen-voting-set.md) and the Amendment section of
[ADR-0021](../adr/0021-quorum-shrink-and-finality-floor.md). The full former text is in git
history, for example `git show origin/develop:docs/consensus/quorum-shrink.md` (before
#1627 merges) or `git log -- docs/consensus/quorum-shrink.md`.
