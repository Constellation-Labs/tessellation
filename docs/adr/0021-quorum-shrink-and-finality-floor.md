# 21. Liveness quorum-denominator shrink with a finality-only cluster-majority floor

Date: 2026-06-30

## Status

Accepted; superseded in part by [ADR-0035](0035-v35-to-v41-mainnet-migration.md) (decision 1 removed, decision 2 kept)

## Context

A committee can become "consensus-dead" -- gossip-responsive but silent on consensus -- so no leader rotation or certificate can assemble at the stuck key. A shrink rung is needed for liveness: lower the threshold enough to rotate the dead leader and assemble a liveness certificate.

But a *uniform* Core-sized quorum let a minority Core (e.g. 2-of-5) self-finalize a snapshot -- a real fork, and the gl0 e2e blocker.

The obvious fix -- floor the quorum at cluster-majority everywhere (the original design doc enumerated ~15 sites including VCC / TC / B1 / B2) -- was **explicitly rejected**: flooring the liveness decision recreates the very leader-rotation / eviction deadlock the shrink rung exists to break, and introduces an assembler/validator asymmetry. The site-map also used `coreCommitteeSize` (a *size*) as a *threshold*, which is dimensionally wrong (it forces unanimity at N=5).

## Decision

Split the quorum computation into two decisions that reference the **same frozen committee**:

1. **Liveness decision** (`quorumShrinkDecision`, `applyClusterFloor = false`): Core-sized, byte-identical, drives VCC / TC / B1 / B2 / `StallDetector`. After `quorumShrinkActivationViews * viewInterval` of wall-clock silence, deterministically lower the quorum **denominator** (not the committee) at the stuck key, anchored on the most-recent `controllerEvidence.completedSigners INTERSECT roundStartFacilitators`. This keeps the rung that lets a stuck committee rotate a dead leader.

2. **Finality decision** (`quorumFinalityDecision`, `applyClusterFloor = clusterFloorActive`): floors `requiredQuorum` at cluster-majority everywhere a snapshot is **committed** (the `maybeGetAllDeclarations` phase gate and the dag-l0 finalization gate). `clusterFloorActive` defaults `false`; gl0 and ml0 override it to `!isInBootstrap`.

The effects of liveness certificates are transitively safe because they only *land* via a finalized snapshot, and finalization is floored.

## Consequences

- Outside bootstrap, the floor neutralizes the shrink rung so the cluster **HALTS safely under more than `f` failures rather than minority-finalizing**.
- Liveness certificates stay Core-sized and never recreate the deadlock.
- **Cost:** two parallel quorum computations over one frozen committee; a subtle invariant that is easy to get wrong -- *a finality gate is a pair (threshold, frozen voter universe), not a threshold alone.*
- `quorumShrinkActivationViews` is consensus-critical and was the `PosInt -> Int` config bug that blocked the alpha.158 startup: `0` (meaning "disabled") was rejected by the refined type. A `ConfigLoadSuite` now parses the packaged config the way the node does at startup so this fails as a red test, not a deploy-time exception.

Source: the two-decision split lives in `ConsensusStateAdvancer.scala` (the `clusterFloorActive` gate selecting the frozen `roundStartFacilitators` committee versus Core as the phase-gate universe) and `QuorumDenominatorShrink.decide`; gl0 and ml0 set `clusterFloorActive = !isInBootstrap`.

Mechanism reference: `docs/consensus/quorum-shrink.md`.

## Amendment (2026-10-10, ADR-0035 / #1627)

Decision 1, the liveness quorum-denominator shrink rung, is removed together with the pre-v35
Global L0 engine. `QuorumDenominatorShrink`, the `quorum-shrink-activation-views` knob and the
`dag_consensus_quorum_shrink_*` metrics no longer exist. Certified consensus (ADR-0032) never lowers
the quorum denominator at a stuck key: under more than `f` failures of the frozen committee the round
halts, which is the outcome decision 2 already forced outside bootstrap.

Decision 2, the finality floor, is kept. `FinalityQuorum.required`
(`node-shared/.../infrastructure/consensus/state/FinalityQuorum.scala`) returns
`max(coreQuorum, clusterFloor)` over the frozen `roundStartFacilitators`, and
`ConsensusStateAdvancer` still selects the phase-gate universe from `clusterFloorActive`. Global L0
now overrides `clusterFloorActive` to a constant `true` (`GlobalSnapshotConsensusStateAdvancer.scala`),
so the committee floor also applies during bootstrap. Liveness certificates (VCC/TC assembly and
validation, stall feasibility) keep the Core-sized `FinalityQuorum.coreQuorum`. The base default of
`clusterFloorActive` remains `false` for any advancer that does not override it.

The `ConfigLoadSuite` consequence above is history: the knob it guarded is gone. The mechanism
reference `docs/consensus/quorum-shrink.md` is now a removal stub.
