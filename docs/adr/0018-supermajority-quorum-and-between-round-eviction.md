# 18. Supermajority quorum, no mid-round eviction, between-round penalties

Date: 2026-06-30

## Status

Accepted

## Context

With unanimity (`N/N`) phase progression, any single silent or slow peer wedged the round. Several thresholds were tried and discarded:

- `(N/2)+1` -- a fast clique splits the network at startup;
- blanket `ceil(N * 2/3)` always -- still lets `N-1` peers outrace one slow node;
- unanimity-then-quorum-after-`viewNumber > 0` -- `viewNumber` desync gives different thresholds on different nodes.

The intuitive fix for silent peers -- evict them mid-round -- was attempted in many forms: a polled `EvictionVoteTracker`; timeout-certificate-driven mid-round eviction; `didStall` toggles; immediate view-change at `stallCount == 0`; leader-chosen in-round `effectiveCommittee` shrinkage. **Every one forked the network.** The reason is structural: different nodes detect different missing peers at different times, so any mid-round mutation of the facilitator set diverges that set across honest nodes -> divergent `facilitatorsHash` -> permanent split.

## Decision

1. **Supermajority phase progression.** A phase completes at supermajority, computed by a single `QuorumPolicy.fromFraction` function over integer arithmetic: `2/3 -> ceil(N * 2/3)`; `1.0 -> unanimity(N)`. The fraction is consensus-critical and folded into `deterministicConfigHash`; unsupported fractions fail fast when the quorum policy is evaluated (`QuorumPolicy.fromFraction` throws), and the packaged per-env defaults are covered by `ConfigLoadSuite`. dag-l0 ships `0.6666666666666666` (the max-precision `Double` approximation of exact `2/3`), NOT `0.67`: the rounded `0.67` rounds up unfavorably for `N` divisible by 3 (e.g. `N=6`: `ceil(6 * 0.67) = 5` instead of the BFT-intended `ceil(2N/3) = 4`), which wedged rounds at 4-of-6 and also wedged the VCC escape hatch, since it uses the same threshold. currency-l0 uses `1.0` (see ADR-0027 on hypergraph-vs-metagraph scaling).

2. **No mid-round facilitator eviction, of any kind.**

3. **Between-round eviction.** Non-signers are penalized in the *consensus-agreed outcome* and excluded from the *next* round's selection. The between-round penalty IS the eviction.

Modeled explicitly on HotStuff / Flow Jolteon / Aptos DiemBFT rather than invented (ADR-0027).

## Consequences

- Missing peers no longer stall a round; liveness no longer sits behind the slowest BFT peer.
- Deterministic membership is preserved -- the fork class above is closed by construction.
- **Cost:** a finalized round may carry fewer than `N` proofs; reward fairness for late-but-honest signers is handled by the signature-grace window (ADR-0020) and the Core/Tier-1 reward split (ADR-0019).
- Penalty state (`removalPenalties`, `deferralCountdown`, `readmissionCountdown`) becomes outcome state and must obey ADR-0016 (derived only from the consensus-agreed outcome).
- **Rejected alternatives** are retained in the docs for context: `docs/consensus/eviction-cert-deterministic-shrinkage.md` and `docs/consensus/liveness-shrink-permissioned-fallback.md`.

Mechanism reference: `docs/consensus/README.md` (section 5, "multi-committee quorum").
