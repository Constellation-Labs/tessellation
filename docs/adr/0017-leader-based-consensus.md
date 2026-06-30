# 17. Leader-based consensus and deterministic facilitator selection

Date: 2026-06-30

## Status

Accepted

## Context

The pre-v4 consensus was a 5-phase all-to-all lock / unlock voting protocol. When more than half the facilitators were unresponsive, the unlock thresholds became mathematically impossible to reach, repeatedly driving `facilitatorCount` toward zero -- catastrophic cluster death. At least five distinct deadlocks were attributed to the lock/unlock mechanism.

Facilitator selection was external to the consensus: L0 picked the facilitator set and stored it in the Global Snapshot for L1 to fetch and run with (ADR-0005, ADR-0006). This coupled L1 liveness to L0 snapshot delivery and gave the selection no determinism guarantee internal to the round.

## Decision

Adopt a leader-based 3-phase consensus with a designated leader per view:

`CollectingFacilities -> CollectingProposals -> CollectingSignatures -> Finished`

The `UnlockConsensusUpdate` machinery is removed and replaced by a `ViewChangeManager` + `StallDetector` + `AbandonmentTracker` triad (leader rotation, stall detection, round abandonment).

Facilitators -- and the per-view leader -- are selected deterministically *inside* global consensus using Highest-Random-Weight (rendezvous) hashing:

score each peer `SHA-256(entropy ++ peerId)`, order ascending, and take the first `M` (`M = maxFacilitatorCount`). The per-view leader is chosen by score rank, rotated with `viewNumber` (view 0 -> rank 0, view 1 -> rank 1, and so on).

Properties: deterministic across honest nodes, IID-uniform, zero cross-round autocorrelation, no implicit majority coalition. (The sort direction is an implementation detail -- the distribution is direction-independent -- but the docs state ascending / first-`M` to match `FacilitatorSelector.scala`.)

## Consequences

- Removes the all-to-all unlock thresholds that became infeasible under failure; removes external facilitator provisioning.
- **Supersedes ADR-0005 and ADR-0006.**
- Introduces **leader stall** as a new first-class failure mode, addressed by the view-change protocol (ADR-0020) and the liveness-shrink mechanism (ADR-0021).
- Makes committee membership a first-class consensus concern, requiring leader-eligibility gates and tiering (ADR-0019) and a deterministic eviction/re-admission path (ADR-0022).
- Selection determinism depends on the entropy and candidate set being consensus-agreed (ADR-0016).

Mechanism reference: `docs/consensus/README.md` (sections 9-10, Rendezvous Hashing).
