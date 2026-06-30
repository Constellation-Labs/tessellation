# 22. Deterministic eviction (B1) and re-admission (B2) certificates

Date: 2026-06-30

## Status

Accepted

## Context

Between-round eviction (ADR-0018) needs a deterministic, fork-safe mechanism both to remove chronic non-signers and to re-admit peers whose penalty has expired.

- Early per-round *local* eviction was non-deterministic and forked the network.
- Re-admitting penalty-expired peers *before* they demonstrated current-tip participation caused a post-restore deadlock (ord-33) and `facilitatorsHash` forks.
- An Apr-29 three-hour wedge (alpha.43, ord 3110065) showed the certificate witness pool must be the wider `eligibleFacilitators`, not the committee: the numerical quorum was met, but stripping chronic signers (present in `eligibleFacilitators` but not the committee) left fewer committee votes than the threshold, so the certificate could never assemble.

## Decision

**B1 (eviction).** Committee members emit an `EvictionVote`. A quorum assembles an `EvictionCertificate`, which is embedded in the NEXT leader's `Proposal` and applied only at proposal acceptance of the next ordinal -- never at the stuck ordinal. Every node that sees the proposal sees the certificate and applies the same eviction. Targets are capped at `committee.size - minQuorum` per certificate to prevent over-eviction.

**B2 (re-admission), symmetric.** A peer whose `removalPenalty` expires enters `readmissionCountdown` probation. An `AdmissionVote` quorum assembles an `AdmissionCertificate` (embedded in the next proposal, applied at the next ordinal) that clears probation. This is the ONLY path out of probation: probation peers are excluded from `fullBase`, `potentiallyCompeting`, and the `withoutPenaltiesOnly` escape, and there is a separate quorum floor that excludes them.

For both B1 and B2, the certificate **witness pool is `eligibleFacilitators - target`** (the eviction / admission target itself is excluded), consensus-agreed and re-derived locally by followers -- the certificate payload does not carry the pool -- while the quorum **threshold stays committee-sized**. (The view-change certificates VCC / TC use a *wider* pool, unioned with `roundStartFacilitators`; see ADR-0020.)

## Consequences

- Deterministic, fork-safe eviction and re-admission (apply-at-next-ordinal means the certificate rides the proposal every node validates).
- Re-entry requires *demonstrated current-tip participation*, proven by a consensus-agreed certificate.
- **Cost:** additional declaration and certificate types plus outcome bookkeeping (`removalPenalties`, `readmissionCountdown`). B2 is structurally present but inert on currency-l0, where the quorum is `1.0` (metagraph; ADR-0027).
- Depends on ADR-0016 (consensus-agreed pools/penalties), ADR-0018 (between-round eviction), ADR-0019 (tiers / evidence window).

Mechanism reference: `docs/consensus/README.md` (sections 6, 11).
