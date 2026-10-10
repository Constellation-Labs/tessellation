# ADR-0036: Single frozen voting set and round-robin leaders for Global L0

Date: 2026-10-10

## Status

Proposed

Supersedes [ADR-0019](0019-tiered-committee-and-participation-evidence.md),
[ADR-0021](0021-quorum-shrink-and-finality-floor.md) (the quorum shrink; the finality floor survives as the only
quorum) and [ADR-0030](0030-broad-tier1-signing-leases-and-canonical-admission.md). Amends
[ADR-0022](0022-eviction-and-readmission-certificates.md), [ADR-0028](0028-delegated-validator-reward-recipients.md)
and [ADR-0032](0032-certified-consensus-outcomes.md).

## Context

The v4.1 Global L0 committee was split into a quorum-bearing Core, a signing Tier 1 and a Witness pool. Core
membership, leader eligibility and admission scores were derived from participation evidence that the round leader
reported: `observedResponders` (who sent a Facility), aggregated self-health hints, and controller scores computed
from them. The leader's report was authenticated (it signed it) but not verifiable: no other member could prove that
an omitted peer had actually responded.

The 2026-10-09 forensic review (C-1) showed what follows from that. A leader, or a sequence of leaders, could omit
honest responders or report them unhealthy. That demoted them out of Core and out of leader eligibility, which
concentrated both quorum and leadership in the reporting group. Quorum shrink (ADR-0021) amplified it: a shrunken
Core could finalize with a cluster minority until the finality floor was added. The floor fixed safety but left the
tiers in place; the tiers then mattered only for liveness accounting and leader choice, which is exactly where the
unverifiable input entered.

Known-safe BFT protocols (HotStuff and its descendants, Tendermint, Aptos/Diem) use one validator set per epoch, a
quorum of more than two thirds of that set for every certificate, and a leader schedule that is a deterministic
function of the set and the view. None of them classify validators from leader-reported observations inside the
epoch.

## Decision

1. **One frozen voting set.** Each round's voting set is `roundStartFacilitators`, frozen at round start. Every
   phase gate, prepare and commit QC, view-change and timeout certificate, admission certificate and the finalization
   gate uses the same quorum: `max(1, ceil(n * quorumThresholdFraction))` with the configured two-thirds fraction
   (`FinalityQuorum.of`). There is no Core, Tier 1, Witness or quorum shrink. Under more than `f` failures the round
   halts; it never lowers the denominator.
2. **Round-robin leaders.** `LeaderSchedule.leader(committee, ordinal, view)` indexes the sorted committee at
   `(ordinal + view) mod n`. Round creation, view-change application and timeout application all call it. No
   snapshot hash, score or health input feeds leader choice.
3. **No leader-reported authority inputs.** `observedResponders`, `observedSelfHealth`, `Facility.selfHealthHint`,
   controller evidence, quality scores and tier transitions are deleted from consensus, the wire and the
   operational state. `LocalHealthMonitor` remains an operator gauge only.
4. **Explicit successor rule.** The next committee is `(B \ (D u E)) u A`: `B` the frozen committee, `E` certified
   evictions, `A` certified admissions, `D` deterministic exits (members no longer seedlisted or no longer
   collateral-eligible). It is computed once by `CertifiedNextRoundProjector` and certified as
   `nextRoundAuthority`; incumbents are never truncated by hash order, and the configured cap only blocks growth past
   `max(cap, |B|)`.
5. **Deterministic candidate rotation.** Admission nomination, admission-certificate selection, audit targets and
   probation probes rotate through PeerId-sorted candidates by ordinal (`DeterministicRotation`) instead of hash
   ranking.
6. **Wire shape.** `CertifiedConsensusSchema` v36: `ProposalValue`, `CertificationStatement` and
   `CertifiedRoundAuthorityV1` carry no Core set or hash; `CoreCommitQC` becomes `CommitQC` over the frozen
   committee. `consensusSchemaVersion` 37. These fields never existed on a deployed network (mainnet runs v3.5 and
   launches directly on the certified path from the recovery-seed root; testnets return through fresh genesis), so
   they are dropped without compatibility shims. Per ADR-0034 this is a schema/wire change.
7. **Cluster-wide signature grace.** `signatureGracePeriod` enters `deterministicConfigHash`, because it bounds
   which commit signatures land in certified commit evidence.

8. **Membership clearing** (gate `fields-added-ordinals.gl0-clearing`: dev 0, every public network 9999999 until
   scheduled). A member that stays seated but is absent from the certified commit evidence is removed
   deterministically:
   - **Evidence.** When round N+1 is derived, every member of round N's frozen committee gains one seated round and,
     if it signed round N's commit QC *as embedded in N+1's `certifiedLineage`*, one present round. No locally
     assembled QC is ever used. The commit QC keeps every valid commit received before finalization, bounded by the
     cluster-wide `signatureGracePeriod`.
   - **Intervals.** `interval(N) = N / 2048`. An interval counts for a member only if it was seated for every round of
     it. A counted interval with `present * 10 < seated` extends the member's low-presence streak; any other resets
     it. Records exist only while a member is seated; re-admission starts a fresh record.
   - **Clearing.** A member with a streak of 3 is pending. At most one member is cleared per interval: lowest presence
     ratio, then lowest PeerId. The target is a derived part of the `ProposalValue`'s `evictedPeers`; followers
     re-derive it, so a leader can neither skip an eligible target nor substitute another.
   - **Floor.** `min-committee-size` (mainnet 10, testnet 4, integrationnet 4, dev 3; at least 3) bounds contraction
     only. Above the floor the target leaves unpaired. At the floor it waits for one open ReadyAtTip replacement,
     which may be admitted off-cadence and without expansion headroom because the size is unchanged; a value that
     uses that exception without the paired clearing eviction is invalid. The floor never blocks admission.
   - **Cooldown.** A cleared member's `penaltyUntil` is one interval (2048 ordinals). Active penalties now block every
     admission certificate, probation recovery included; both horizons expire by ordinal comparison.
   - **Retired.** The fast finality-participation auditor and its paired atomic-replacement intent are removed;
     clearing is the only health-based removal path.
   - **Omission bound.** Leaders rotate over every member, so a coalition must both omit an honest member's commits
     and author at least 90% of finalized children for three consecutive intervals (6,144 rounds) to clear it. This
     holds conditional on timely delivery of honest commits to honest child proposers; outside that assumption,
     delayed commits can cause false clearing without any coalition, because accepted certificates need not contain
     every member. Operators watch the per-member presence gauge.

## Consequences

- Leader-reported content can no longer change who votes, who leads, the quorum size or who is paid. What a leader
  still chooses, which quorum of signatures it embeds and which timeout voters it reports, has no effect on
  authority; the C-1 replay regression in `GlobalCertifiedLineageReplaySuite` pins this.
- Liveness now needs more than two thirds of the whole committee, community seats included. Community nodes
  can therefore halt the chain if more than a third go offline at once. This is accepted: halting is safe, and
  recovery uses the permissioned recovery-seed procedure.
- An offline member keeps its seat and its leader turns until it exits through `D` or clearing (about three
  intervals). Its leader turns cost one view change each, and because the whole committee never signs, every round
  waits the full `signatureGracePeriod` (3 s) after quorum before finalizing. The earlier 750 ms Tier-1 window
  applied only once Core had signed.
- `LeaderSchedule` indexes the PeerId-sorted committee, so an operator who can grind keys into adjacent sorted
  positions gets consecutive leader slots, and consecutive views within one round. Admission is seedlist-gated, which
  makes this mostly theoretical, and a run of `k` adjacent Byzantine leaders still costs at most `k` view changes.
- Rewards follow the frozen committee, as already decided in ADR-0028 / #1627.
- A large body of configuration and code is removed: `core-committee-size`, the active-admission score and penalty
  keys, leader-quality floors, chronic reinstatement, quality decay, the Tier-1 grace window and the bootstrap
  threshold knob (now the constant `BootstrapCommittee.MinimumSize = 3`).
