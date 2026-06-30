# 19. Three-tier committee and consensus-agreed participation evidence

Date: 2026-06-30

## Status

Accepted

## Context

A flat, equal-weight facilitator set lets unproven or degraded peers count toward the liveness quorum and wedge it. The tier / quality / chronic-miss signals used to filter them were originally tracked in locally-accreting per-peer state, copied round-over-round and seeded from a local sidecar on restart -- a fork hazard, because the signals were not byte-identical across nodes (a direct violation of ADR-0016).

## Decision

Partition the active set deterministically every round into three tiers:

- **Core** -- gates the **liveness** quorum (phase progression, leader rotation, certificate assembly) and is the leader pool;
- **Tier-1** -- signs and earns rewards alongside Core, and its declarations ARE counted toward snapshot finalization outside bootstrap;
- **Witness** -- observes, and may witness certificates (ADR-0022).

Two distinct quorum gates apply (see ADR-0021):

- **Liveness** is gated on Core only: `q = ceil(coreFacilitators.size * quorumThresholdFraction)`. A Core that has shrunk to a cluster minority can still rotate leaders and assemble liveness certificates.
- **Finalization** is a separate gate. During bootstrap it keeps the legacy Core-sized strict-majority behavior; outside bootstrap it counts declarations over the **frozen `roundStartFacilitators` committee** (Core + Tier-1), so a minority Core cannot finalize a divergent snapshot.

Rewards follow signing: `Rewards.distribute` splits the pool evenly across the signers in `lastArtifact.proofs`, with no Core-vs-Tier-1 stratification in today's reward math.

All tier / quality / chronic signals derive from `controllerEvidence` -- a bounded *signed* window on the snapshot (`{ roundStartFacilitators, completedSigners, admittedPeers, timeoutVoters, ... }`) -- never from local state. Specific rules:

- **Chronic-core replacement ladder:** exclude chronically-missing Core peers (demote to Tier-1); replace one-for-one from non-chronic Tier-1 reserves by active score; floor to `coreCommitteeSize`; shrink if supply is short (a smaller healthy Core beats a larger chronic-padded one); re-admit least-bad chronic peers only if a healthy Core would otherwise drop below `MinViableCoreSize` (2).
- **Demotion window:** a Core peer is demoted only after absence from ALL of the last `DemotionConsecutiveMisses` (3) *completed-round* signer sets. A single slow round does not demote; failed rounds do not cascade-demote.
- **Leader eligibility:** two gates with fallback -- graduation (`participated >= minParticipationObservations AND completed >= 1`, the "kick-fast" gate so a never-finalizing peer is never lead-eligible) and recent-signer (present in every one of the last 3 signer sets). If either gate drops the pool below `minLeaderPoolSize`, fall back to the broader set to preserve liveness in small / cold-start clusters.

## Consequences

- Unproven and degraded peers cannot wedge the quorum; the signals survive cold restart because they are consensus-agreed (ADR-0016).
- Reward distribution is decoupled from the liveness quorum.
- **Cost:** a large, consensus-critical, per-environment tuning surface (`coreCommitteeSize`, `MinViableCoreSize`, `DemotionConsecutiveMisses`, `minLeaderPoolSize`, promote/retain/demote hysteresis bands). Mis-tuning trades liveness against safety; values differ by network (ADR-0027).

Mechanism reference: `docs/consensus/committee-tiers.md`.
