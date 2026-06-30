# 20. Two-track view change: view-change and timeout certificates

Date: 2026-06-30

## Status

Accepted

## Context

In a leader-based protocol (ADR-0017), when a leader stalls the cluster must rotate to a new leader *at the same key* without forking. The original single-track `ViewChangeCertificate` path, with a local-increment fallback, was fragile when votes were sparse. An early polled `EvictionVoteTracker` raced its poll cadence against gossip arrival and round abandonment, and for several alphas a gossip-level `StallReport` handler was simply missing ("Unhandled rumor"), so stalls were never acted on.

## Decision

On stall, emit BOTH declarations in parallel:

- **Track 1:** a `ViewChangeVote` -> `ViewChangeCertificate` (VCC);
- **Track 2:** a `TimeoutVote` -> `TimeoutCertificate` (TC), HotStuff-style.

Whichever certificate assembles first advances the view. TC formation is **event-driven** (the gossip handler calls `addStallReport` immediately), never polled.

Supporting rules:

- `TimeoutVote` carries a `TimeoutReason` (`NoProgress | QuorumInfeasible`); votes and certificates are grouped and certified per reason, so the reason is part of the certificate's identity and replays across failure modes are prevented. (Today all in-tree `performViewChange` callers pass `NoProgress`; `QuorumInfeasible` is defined in the ADT but no call site emits it yet.)
- Both VCC and TC carry `highestKnownQc: Option[ProposalQC]`, so a vote-locked proposal can commit across the view change: the next leader must propose that hash or abort and hand off.
- A proposal with `view > 0` must carry **exactly one** of `vcc` / `timeoutCertificate` (both -> rejected; neither -> rejected outside the solo-Core and round-start-view exceptions).
- Votes sign the canonical hash of the **frozen** `roundStartFacilitators`, not the live (mutable) facilitator set, so honest nodes that observed different mid-round state still certify together (ADR-0016).
- **View-from-time pacemaker:** each `Facility` carries `proposerClockMs`; the leader takes the *median* across the accepted Facilities as `consensusEndTime` (clamped against `parent.consensusEndTime + 1` for anti-regression), recorded into the outcome's `recentRoundEndTimes` (computed once, re-read by followers per ADR-0016). The next round derives a `timeView` from this anchor, used **only as a pacemaker timeout hint** that wakes the round to emit a signed view-change vote. The proposal-critical view still starts at `0` and advances only via a quorum-certified VCC or TC; the time anchor never advances the view by itself.

## Consequences

- Robust leader rotation: two independent assembly routes, plus a time anchor that does not depend on vote density.
- HotStuff-aligned safety via QC carry-forward and frozen-committee signing.
- **Cost:** two certificate types and a mutual-exclusion rule add protocol surface; `viewInterval` and the grace timers are tuning knobs; the earlier per-`recentRoundEndTimes` divergence (ADR-0016) shows the time anchor must be handled exactly per the re-read rule.

Mechanism reference: `docs/consensus/timeout-certificate.md`, `docs/consensus/view-from-time-anchor.md`.
