# 21. Liveness quorum-denominator shrink with a finality-only cluster-majority floor

Date: 2026-06-30

## Status

Accepted

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
