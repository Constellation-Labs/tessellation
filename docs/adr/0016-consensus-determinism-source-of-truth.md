# 16. Consensus determinism source-of-truth invariant

Date: 2026-06-30

## Status

Accepted

## Context

Consensus artifacts (snapshots, certificates) are hashed and must be byte-identical across honest facilitators. Any divergent byte produces a divergent hash, which prevents a quorum from forming over the artifact, which forks or freezes the cluster.

Across the v4.0.0 -> v4.1.0 stabilization, multiple production incidents traced to the *same* root cause: a value derived from local observation leaked into a consensus-critical derivation.

- **Ord-5 facilitators-hash fork.** The next round's committee was derived from a locally-shrunken, post-withdrawal facilitator set. A late `DECL_WITHDRAWN` that landed *before* the `CollectingSignatures -> Finished` boundary on some nodes and *after* it on others made them record different `facilitators` counts, hence different deferral maps, hence a different committee at N+1 -> divergent `facilitatorsHash`.
- **Cold-start freeze (`peerHistory`).** The v19 view-from-time `recentRoundEndTimes` value -- a *median of per-node wall clocks* (`proposerClockMs`) -- was folded into the **hashed** `ConsensusOperationalState.peerHistory`. Because it was hashed it had to be byte-identical; the median-of-local-clocks diverged 3-vs-2 at ordinal 4, making a 4-of-5 quorum unreachable.

These are not separate bugs. They are one missing invariant.

## Decision

Any value that either (a) enters a hashed consensus artifact, or (b) drives committee / quorum / leader derivation, MUST be a pure function of consensus-agreed inputs:

- the prior signed outcome,
- the frozen round-start committee,
- signed declarations received in the round, or
- a shared deterministic anchor that is *computed once by the leader, stored in the outcome, and re-read by followers* (never recomputed locally).

Such a value must NEVER be derived from local observation, local counters or timers, wall-clock reads, live cluster membership, or per-node sidecar state.

Fields that are inherently locally-divergent (per-peer scores, `recentRoundEndTimes`, per-peer maps) are split out as **operational** fields: emitted empty / `None` in the signed bytes and rebuilt round-by-round from the signed evidence window. Fields that must ride the proposal bytes are **proposal-critical** and obey the rule above.

## Consequences

- Eliminates an entire class of fork/freeze bugs whose signature is "diverges only under timing/ordering differences between honest nodes."
- Enables cold-restart resilience: proposal-critical fields survive a restart in the signed history; operational fields rebuild deterministically.
- **Cost:** every new field added to a consensus artifact must be explicitly classified proposal-critical vs operational, and contributors must internalize the rule. Convenient local signals (e.g. real-time host health) can only be used after "laundering" them through consensus-agreed propagation -- see ADR-0019's self-health path, where peers self-report on their `Facility` and the leader aggregates the result onto the `Proposal`.
- This invariant is load-bearing for ADR-0018, ADR-0019, ADR-0020, ADR-0021, ADR-0022, and the MPT replay gating in ADR-0025. It is the most-cited single rule in the v4.1.0 work.

Mechanism reference: `docs/consensus/README.md` (sections 4, 9-10), `docs/consensus/committee-tiers.md` (Participation Evidence).
