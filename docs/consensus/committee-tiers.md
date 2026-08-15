# Tiered Committee Architecture (Core / Tier-1 / Witness)

This is the as-shipped reference for the three-tier committee model introduced
in the post-v4.0.0 consensus rewrite (the "v19" multi-committee derivation). It
is the single largest behavioral change since v4.0.0 and replaces the flat
rendezvous-hashing facilitator set described in [README sections 9-10](README.md#9-facilitator-selection).

Every consensus round now partitions its facilitators into three deterministic
tiers. **Core (Tier 2)** exclusively controls leaders and liveness certificates.
**Tier 1** signs the snapshot and earns rewards and witnesses certificates, but
does not count toward the certificate quorum denominator. Once the full-committee
floor is active, the frozen Core+Tier-1 committee controls Facility-phase
progression and artifact finality. **Witness (Tier 0)** observes only.
The partition is derived every round from consensus-agreed signed state, so two
honest nodes deciding the same round compute byte-identical committees; this is
load-bearing, because the committee feeds `roundStartFacilitators` ->
`facilitatorsHash`, and a divergent committee forks the cluster. This document
covers how the tiers are built, how the round's active set is admitted, the
deterministic witness pool, the chronic-classification evidence layer, the tier
transitions, and how rewards are distributed to the committee.

All source paths below are under
`modules/node-shared/src/main/scala/io/constellationnetwork/node/shared/infrastructure/consensus/`
unless noted otherwise. The dag-l0 wiring lives in
`modules/dag-l0/.../snapshot/GlobalSnapshotConsensusStateCreator.scala`
(the currency-l0 StateCreator wires the same primitives).

## Table of Contents

1. [The Three Tiers](#1-the-three-tiers)
2. [Building the Round's Active Set (ActiveFacilitatorAdmission)](#2-building-the-rounds-active-set-activefacilitatoradmission)
3. [Partitioning into Tiers (CommitteeBuilder)](#3-partitioning-into-tiers-committeebuilder)
4. [The Witness Pool](#4-the-witness-pool)
5. [Participation Evidence and Chronic Classification](#5-participation-evidence-and-chronic-classification)
6. [Tier Transitions](#6-tier-transitions)
7. [Leader Eligibility](#7-leader-eligibility)
8. [Rewards and the committee](#8-rewards-and-the-committee)
9. [How It Wires Into a Round](#9-how-it-wires-into-a-round)

---

## 1. The Three Tiers

The tier integers are defined in `TierTransitions.scala:42-49`:

| Tier | Constant | Value | What it can do |
|------|----------|-------|----------------|
| Core | `TierTransitions.Core` | `2` | Full facilitator. Liveness certificates derive their normal denominator from Core. Only Core peers are eligible to **lead** a round. |
| Tier 1 | `TierTransitions.Tier1` | `1` | Witness-eligible (B1/B2/VCC/TC witness pool). Tier-1 peers **sign** each round's `signedMajorityArtifact` and earn a delegated validator share while seated. They cannot lead or count toward liveness-certificate quorums, so an individual silent Tier-1 peer cannot wedge leader rotation. Outside bootstrap, Tier-1 remains in the frozen committee used by the Facility-phase and finality floors. |
| Witness | `TierTransitions.Witness` | `0` | Observation only. Open membership; in the v19 transition path peers fall here only via explicit eviction. |

The key split is: **Core exclusively controls leaders and liveness certificates;
the frozen Core+Tier-1 committee controls Facility-phase progression and artifact
finality outside bootstrap.** The liveness-certificate threshold is
`q = ceil(coreFacilitators.size * quorumThresholdFraction)` (see
`CommitteeBuilder.scala:13-16` scaladoc and `QuorumPolicy`). The round-start
committee carries `coreFacilitators` and `tier1Facilitators` separately
(`GlobalSnapshotConsensusStateCreator.scala:729-730`,
`coreFacilitators = CoreFacilitators(committees.core)`). No individual Tier-1
peer, and not every Tier-1 peer, is required under the shipped two-thirds floor;
however, more than one-third silent frozen seats can halt Facility progression or
finality. That fail-closed boundary is deliberate and requires operator recovery
once participation is already below the current committee quorum.

Snapshot finalization is a separate gate. During bootstrap it preserves the legacy
Core-sized/strict-majority behavior. Outside bootstrap, finality uses the frozen
`roundStartFacilitators` committee floor (`quorumFinalityDecision`), so a Core that has
shrunk to a cluster minority can still rotate leaders or assemble liveness certificates
but cannot finalize a divergent snapshot. The counted signer set is the frozen
committee, not a locally mutated post-eviction subset.

### Reward and signer pool

Delegated rewards go to the frozen round-start signing committee, not the proof
subset. Core and Tier-1 peers split the static validator pool evenly; there is no
Core-vs-Tier-1 stratification and no admission-score payout filter. Classic rewards
retain the historical `lastArtifact.proofs.map(_.id)` signer rule. See
[rewards.md](rewards.md) for activation gates, including the full-committee correction
ordinal, and diagnostics.

---

## 2. Building the Round's Active Set (ActiveFacilitatorAdmission)

`ActiveFacilitatorAdmission` classifies which deterministically selected peers are
eligible for Core. It does **not** define the entire signing/reward committee.
`ConsensusPeerController.retainSelectedForSigning` retains the canonical parent
membership as Core or Tier 1 unless an existing explicit eligibility/removal rule
applies. The controller's chronic-miss input is based on the early Facility responder
set; it can remove Core/leader eligibility but cannot prove snapshot-signing failure
or delete a Tier-1 reward seat.

`fromRecentSigners` takes the deterministically-`select`ed candidate list and
scores each peer from recent-signer evidence and `peerQuality`. It produces an
`active` list plus a rich exclusion taxonomy (`ExclusionReason`,
`ActiveFacilitatorAdmission.scala:20-29`):

- `NotRecentSigner` -- not present in the recent-signer window.
- `QualityBelowThreshold` -- `participated >= minParticipationObservations` but
  `completed/participated` is below `minParticipationRatio`.
- `ScoreBelowPromoteThreshold` / `ScoreBelowRetainThreshold` /
  `ScoreBelowDemoteThreshold` -- the three hysteresis bands. A recent signer is
  retained while its score stays above the demote/retain thresholds; an expansion
  candidate must clear the (higher) promote threshold to be newly admitted. The
  default bands are `promote=100`, `retain=70`, `demote=40`
  (`ActiveFacilitatorAdmission.scala:65-67`), mirroring the evidence weights in
  [section 5](#5-participation-evidence-and-chronic-classification).
- `BeyondTarget` -- a qualified peer outside the controller's Core-classification
  target. It remains eligible for a Tier-1 signing lease.
- `CertifiedTimeoutMissing` -- used by the `fromCertifiedTimeout` path
  (`ActiveFacilitatorAdmission.scala:242-285`), which shrinks the active set to
  the certified timeout voters plus a deterministic recent-signer fill when a
  quorum has independently timed out. This ties admission to the
  Timeout-Certificate view-advance path.

Two controller lanes widen the Core-eligible classification beyond the sticky
recent-signer pool:

- **Reserve lane** -- qualified promote-threshold peers admitted to fill the
  remaining `target` slots (`reserveAdmitted`,
  `ActiveFacilitatorAdmission.scala:196-198`).
- **Probation re-entry lane** -- `minProbationReentrySlots` reserves up to K
  slots for below-promote-threshold rehabilitating peers even when the per-round
  expansion budget is exhausted. A peer that signed the latest round retains
  priority for a bounded probation classification until it reaches the retain band;
  missing the latest round ends that classifier priority, not its signing lease. Existing climbers rank ahead of fresh
  candidates. Probation-admitted peers are non-quorum-bearing: they flow into
  `nonCorePeers` in `CommitteeBuilder` (see below), so widening the lane cannot
  affect quorum feasibility. The lane is inert when
  `minProbationReentrySlots == 0`.

A peer outside these lanes remains Tier 1 when otherwise eligible. Score exclusion
or one missed Facility removes Core/leader eligibility, not the signing lease. The
early Facility responder signal is deliberately not reused as signing-seat eviction
evidence. Under v35, a separate bounded finality audit inspects actual parent-artifact
`MajoritySignature` proofs for every Core + Tier-1 signing peer. Its certificate can
only be consumed with a paired Core-certified ReadyAtTip admission, so this
health-derived path does not shrink the next-round signing roster or finality floor.
Independent deterministic eligibility authority (including seedlist, on-chain
collateral eligibility, and configured facilitator selection) remains unchanged; see
sections 4 and 5.

New leases use a certified two-round path. The round-N leader carries one
rendezvous-ranked candidate in its Proposal. In round N+1, Core members vote for
that parent nominee, and an accepted Core-quorum AdmissionCertificate adds it to
the next parent committee. Open votes and certificates are enabled only on the
existing `activeAdmissionExpansionIntervalRounds` cadence (five rounds in the
shipped Global-L0 config). Before voting, each Core node also requires its actual
local parent proof set, intersected with the current committee, to satisfy the
finality floor for `current committee size + 1`. That proof-dependent check is local
vote-emission policy only; it is never proposal validation or state derivation. It
starts outside bootstrap, alongside the full-committee finality floor. Bootstrap keeps
the legacy Core-only finality gate, so a new Tier-1 seat does not raise the active
requirement and singleton committees remain able to grow under unanimity. The
certificate is the state-transition authority, so a recovered node without the
ephemeral nominee can still accept it. The shipped budget remains one. Monitor ticks
cannot walk to a second candidate after the budget is spent. Probation readmission
is not cadence- or next-seat-headroom-gated and retains its wider witness lane.

`dag_consensus_active_facilitator_fresh_probation_starved` reports whether
sticky candidates consumed the entire probation lane while fresh candidates
were waiting. A transient `1` is expected while a cohort graduates; persistence
beyond the configured score-recovery window indicates that recurring penalties
may be monopolizing the lane and warrants considering a sticky-seat share cap.

The recent-signer lookback depth is `recentSignerWindow`
(`ActiveFacilitatorAdmission.scala:74-81`), floored internally to
`TierTransitions.DemotionConsecutiveMisses` so a low value cannot disable the
recent-signer path. It is intentionally decoupled from the demotion hysteresis:
widening it only changes active-set eligibility, not who is kept out of
quorum-bearing Core.

Wiring: `ConsensusPeerController.chooseActive` produces the Core classification;
`retainSelectedForSigning` retains the signing lease and marks every selected peer
outside that classification as `nonCorePeers`; `CommitteeBuilder` then partitions
the retained set into Core, Tier 1, and Witness.

---

## 3. Partitioning into Tiers (CommitteeBuilder)

`CommitteeBuilder.build` (`CommitteeBuilder.scala:171-352`) takes the active
candidate set plus the carried/derived tier map and participation history, and
returns a `Committees(core, tier1, witness, effectiveTiers, ...)`
(`CommitteeBuilder.scala:106-114`). The three lists partition `candidates`
exactly: every candidate lands in exactly one tier.

### Tier-assignment rule

Each peer's effective tier is computed by consulting, in order
(`CommitteeBuilder.scala:201-209`, scaladoc `:28-40`):

1. **Quality-degradation override.** If `peerQuality(pid)` shows
   `participated >= minObservations` AND `completed/participated < minRatio`, the
   peer is forced Tier 1 regardless of `priorTiers`. This is the structural
   protection: a peer whose cumulative record fell below the bar cannot gate
   liveness even if its carried tier said Core. It is re-derived every round, so a
   peer that recovers its ratio returns to whatever `priorTiers` says next round.
2. **Carried-forward classification.** `priorTiers.get(pid)`, if present.
3. **Quality-proven bootstrap.** For peers absent from `priorTiers` (new joiners),
   Core iff `peerQuality` shows them above `minRatio` with
   `participated >= minObservations`. This lets demonstrated-good peers enter Core
   on first appearance.
4. **Default: Tier 1.** New peers without proven participation join the witness
   pool, not the liveness quorum. This replaces the original v19
   "everyone defaults to Core" bootstrap, which let chronic-but-unclassified
   community peers wedge the cluster.

Probation peers (`nonCorePeers`) are forced to Tier 1 unless they were already
Witness, and are skipped by every Core-promotion mechanism
(`CommitteeBuilder.scala:202-203`, `:136-141`).

`minObservations` and `minRatio` reuse the existing
`minParticipationObservations` / `minParticipationRatio` config knobs
(`CommitteeBuilder.scala:52-53`).

### Core floor

If the derived Core committee is below the per-environment `coreCommitteeSize`,
peers are promoted from Tier 1 deterministically, ranking the Tier-1 pool by
`peerQuality` (descending ratio, then descending completed count, then PeerId lex
tie-break; `CommitteeBuilder.scala:243-257`). At genesis (empty `peerQuality`)
this collapses to pure lex ordering so the cluster bootstraps from scratch.

The floor is consensus-critical: divergent values across operators would derive
divergent Core committees and silently fork. `coreCommitteeSize` is keyed by
`AppEnvironment`, resolved to a flat value at the construction site, and (as of
v20) folded into `deterministicConfigHash`, so a mismatched value is rejected by
the separate consensus-configuration/facility fence (`CommitteeBuilder.scala:49-53`).
It is not the join-time version gate. Join `versionHash` is the hash of the
advertised version string (or `CL_VERSION_HASH` override), while the advertised jar
or assembly bytes are not hashed or compared. The dag-l0 floor argument is
`coreCommitteeSize` (`GlobalSnapshotConsensusStateCreator.scala:559`,
`coreFloor = coreCommitteeSize`).

### Chronic-core replacement ladder

`chronicMisses` (evidence-derived, see [section 5](#5-participation-evidence-and-chronic-classification))
marks peers whose trailing asked-but-silent streak reached `ChronicMissThreshold`.
Before this ladder, the Core floor re-promoted exactly those peers whenever
healthy supply was short -- a peer absent from `completedSigners` for 112
consecutive rounds kept its Core seat via the floor, and with core=4, quorum=3,
and 2 dead members every round abandoned `ready_participation_quorum_infeasible`
(`CommitteeBuilder.scala:55-75`). The ladder, applied in order:

1. **Exclude** (`CommitteeBuilder.scala:222`). Every chronically-missing Core
   member loses its seat (demoted to Tier 1 for the round -- still signs and earns,
   no longer in the quorum denominator).
2. **Replace** (`CommitteeBuilder.scala:230-236`). Each excluded member is swapped
   one-for-one for a non-chronic Tier-1 reserve, highest `activeScores` first,
   PeerId lex tie-break, supply permitting.
3. **Floor** (`CommitteeBuilder.scala:238-257`). The floor tops Core up to
   `coreFloor` from the remaining **non-chronic** reserves only. Chronic peers are
   never floor-promoted.
4. **Shrink** (`CommitteeBuilder.scala:259-260`, implicit). If non-chronic supply
   cannot reach `coreFloor`, Core stays smaller rather than padding with chronic
   peers -- because the quorum is proportional, a smaller all-healthy Core is
   strictly more live than a floor-sized one with dead seats.
5. **Liveness fallback** (`CommitteeBuilder.scala:262-274`). If the healthy Core
   would land below `MinViableCoreSize` (= `2`, `CommitteeBuilder.scala:86-93`),
   the least-bad chronic peers (lowest miss count, PeerId lex tie-break) are
   re-admitted to reach it, so a mostly-dead network still forms committees. The
   target is capped at the pre-ladder size so the fallback can never inflate Core
   beyond legacy behavior. Probation peers stay barred even here.

With an empty `chronicMisses` (fallback regime, or no chronic peers) every step is
inert and the derivation is unchanged (`CommitteeBuilder.scala:74-75`).

### Determinism

Every input is a consensus-agreed signed outcome field (carried `priorTiers`,
`peerQuality`) or a deterministic local computation from one (the candidate set,
produced by the same filtering pipeline on every node). Output is byte-stable
across honest nodes (`CommitteeBuilder.scala:77-80`).

`effectiveTiers` (the tier map after the bootstrap default, the quality-degradation
override, and any Core-floor promotions) is what the StateCreator persists into the
round's view of who is Core vs Tier 1 vs Witness, and is carried forward via
`peerTiers` (`CommitteeBuilder.scala:96-101`, `:337-341`).

---

## 4. The Witness Pool

`WitnessPool` (`state/WitnessPool.scala:5-62`) is the deterministic set of peers
allowed to **witness** (validly sign) a B1 eviction / B2 admission / view-change /
timeout certificate, without entering the certificate's quorum denominator.

In the canonical "committee = signers of the previous snapshot" pattern, when a
supermajority of the committee is offline or stuck in `WaitingForDownload`, the
round can't progress AND the certificate that would rotate the committee can't
assemble (it gates on the same supermajority of the same committee). Letting peers
with proven prior participation witness the cert -- without giving them a vote in
the round itself -- breaks the deadlock without weakening the round's BFT
guarantee. **The quorum denominator stays committee-sized; only the set of valid
witness signers widens** (`state/WitnessPool.scala:7-14`). This is the mechanism
README section 11 refers to as "Witness Pool Widening (commit e1bdfb190, v9)".

The pool is the **union** of two consensus-agreed sources
(`state/WitnessPool.scala:53-62`):

- `eligibleFacilitators` -- derived from the previous (signed) outcome via the
  chronic-classifier; and
- **historical participants** -- any peer whose `participated >= minParticipationObservations`
  in the carried signed `peerQuality`.

For a target-keyed cert (B1/B2), `forTarget` additionally removes the `target` so a
peer cannot witness its own eviction or admission
(`state/WitnessPool.scala:44-50`). The non-keyed `all` is used for VCC view-change.
Two deliberately narrower selectors sit in front of that wider pool. Open admission
is Core-attested. Before v35, probation readmission and Core-target stall eviction
preserve the wider recovery lane while Tier-1 finality-participation eviction is
Core-attested. Under v35, every health-derived Core or Tier-1 replacement target is
Core-attested and must be paired one-for-one with a Core-attested open ReadyAtTip
admission. Assembly and Proposal validation select the same lane. The target cannot
certify its own replacement, while Currency L0 and legacy Global-L0 recovery retain
their separately specified wider witness behavior.

Determinism contract (`state/WitnessPool.scala:16-34`): both inputs are
consensus-agreed (signed in the previous snapshot), and `minParticipationObservations`
lives in `deterministicConfigHash` so a divergent value is rejected by the separate
consensus-configuration/facility fence. That hash is not join `versionHash`, which
hashes the advertised version string (or `CL_VERSION_HASH` override). The result is
a `Set[PeerId]` (order-independent); cert builders
sort the resulting votes into a `SortedSet` for stable serialization. Because
`peerQuality` grows monotonically, the wider pool is a monotone function of round
history, and in steady state with a healthy committee the union is dominated by
`eligibleFacilitators`.

---

## 5. Participation Evidence and Chronic Classification

`ControllerEvidenceDerivation` (`ControllerEvidenceDerivation.scala`) derives all
the per-peer signals the tier and leader logic consumes, purely from the
bounded signed `controllerEvidence` window. This replaces carried-forward
controller state (scores/tiers/quality copied round-over-round and re-seeded from a
local sidecar on restart), whose locally divergent seeds caused the
alpha.92/129/147 `facilitatorsHash` / `peerHistory.perPeer` wedges
(`ControllerEvidenceDerivation.scala:16-21`). Deriving from the signed window makes
the state a function of finalized chain facts only.

### Weights and the derived score

Per evidence entry a peer earns `+SignWeight (20)` when in `completedSigners`,
`-MissWeight (15)` when in `roundStartFacilitators` but not `completedSigners`, and
`+CertWeight (10)` for each certified appearance (`admittedPeers`, `timeoutVoters`).
The windowed sum is clamped to `[0, 150]`
(`ControllerEvidenceDerivation.scala:43-56`, `:82-113`). These magnitudes/thresholds
mirror the `ActiveFacilitatorAdmission` promote/retain/demote bands
(`ControllerEvidenceDerivation.scala:30-37`).

### Derived signals

- **`chronicMisses(evidence)`** (`ControllerEvidenceDerivation.scala:150-158`) --
  per-peer trailing miss counts for peers whose `consecutiveMisses` streak reached
  `ChronicMissThreshold`. `consecutiveMisses`
  (`ControllerEvidenceDerivation.scala:139-142`) counts trailing entries where the
  peer was asked to sign (in `roundStartFacilitators`) but did not (absent from
  `completedSigners`); a signed entry resets the streak, and an entry where the
  peer was not asked breaks it. This is the input to the chronic-core replacement
  ladder.
- **`recentParticipants`** (`:169-199`) -- peers that signed at least one of the
  most recent `window` entries (the demonstrated-live gate). The fixed
  `DemotionConsecutiveMisses`-window variant mirrors the same gate the tier split
  in `derive` applies via its own inline `recentSignerSets` computation.
- **`canonicalCompletedSigners`** (`:361-370`) -- the canonical signer set for a
  finalized round, derived from `roundStartFacilitators` and the leader's
  proposal-carried `acceptedObservedResponders` (a closed set, certified by the
  quorum that signed the proposal), minus certified evictions. It is deliberately
  NOT the locally accreting `signedMajorityArtifact.proofs`, which differ per node
  by gossip arrival order (`:323-359`). This is what makes the evidence window
  itself byte-identical across deciding nodes.

### Actual finality participation audit

`FinalityParticipationAuditor` deliberately does not feed the local proof set into
`controllerEvidence`. At each Global-L0 round start it updates node-local proof-miss
streaks for every peer in the consensus-agreed intersection of the current Tier-1
set and the parent round's canonical committee, then rendezvous-selects one target
from that complete set. Any observed proof resets that peer's streak. Only current
Core nodes emit votes, and a Core node emits the existing `EvictionVote(Silent)` only
after the target has missed three consecutive local proof sets, reusing
`TierTransitions.DemotionConsecutiveMisses`. Reprocessing the same parent is
idempotent; restart, missing parent evidence, or a non-consecutive parent ordinal
clears the local sequence and delays eviction rather than manufacturing a miss.

Honest nodes may have different proof subsets, so they may disagree about whether to
emit. That disagreement is safe: it changes only local vote emission. State changes
only if a Core quorum signs matching, tip-bound votes, an EvictionCertificate is
assembled, and the leader includes it in an accepted Proposal. In this context
`Silent` means "not observed by a Core quorum before their finalization cutoffs," not
"cryptographically proved never to have signed." The accepted eviction enters the
existing penalty/probation/readmission lifecycle and changes a later committee; it
never lowers the current frozen finality floor.

The audit selects at most one target per round to bound gossip. This is not the old
`max-facilitator-count`/300 subsetting mechanism and does not cap Tier-1 size or reward
breadth. A newly admitted Tier-1 peer is excluded until it was actually seated in the
audited parent round.

### Why `ChronicMissThreshold` equals `DemotionConsecutiveMisses`

`ChronicMissThreshold = TierTransitions.DemotionConsecutiveMisses` (= `3`)
deliberately, not coincidentally (`ControllerEvidenceDerivation.scala:115-123`).
Aligning the two means the moment the tier derivation sheds a silent Core peer
(demotes it to Tier 1 after 3 consecutive misses), the chronic classification ALSO
bars the Core-floor from immediately re-promoting it. Without the alignment, the
floor's demote-then-repromote loop reproduced the ordinal-3150040
quorum-infeasible stall. The value is small enough to react within one evidence
window and large enough that a single slow round (GC pause, network blip) does not
strip a healthy peer.

### Read-side switch and persistence

`controllerInputsWithFallback` (`:276-306`) is the single read-side switch: when the
evidence window has at least one entry, scores/quality/tiers/chronicMisses are
derived purely from the signed evidence; when the window is empty (first deploy /
bootstrap / rollback to a pre-deploy snapshot) the carried maps are returned
unchanged. The StateCreators consume the resulting `ControllerInputs` verbatim with
no conditional logic of their own, so the dag-l0 and currency-l0 read sides cannot
drift (`:225-265`, wired at `GlobalSnapshotConsensusStateCreator.scala:308-315`).

The deterministic subset of operational state -- `recentProofSizes`,
`recentSigners`, `controllerEvidence`, `penaltyUntil` -- is written into the
**signed** snapshot artifact via `signedArtifactOperationalState`
(`:380-395`); the locally divergent `perPeer` / `recentRoundEndTimes` fields are
emitted empty/`None` so they stay out of signed bytes. This is why a cold-restarted
cluster no longer loses all peer history. (`peerQuality` itself is carried on the
signed outcome.)

---

## 6. Tier Transitions

`TierTransitions.computeNextTiers` (`TierTransitions.scala:129-154`) produces the
**next** round's `peerTiers` map from the just-finalized round. The single-peer rule
`computeNextTier` (`TierTransitions.scala:101-111`):

```
val current = priorTier.getOrElse(Core)
if (!roundCompleted) current
else if (current == Core && wasInRoundStart && missedRecentConsecutive) Tier1
else current
```

Three deliberate properties:

- **Promotion is by re-derivation, not here.** This function only **demotes**
  Core -> Tier 1. Promotion (Tier 1 -> Core) happens in `CommitteeBuilder` via the
  quality-proven and Core-floor paths, which is why the two defaults are
  intentionally asymmetric: derivation gates Core **entry** on demonstrated
  participation, while round-completion gates **demotion** on demonstrated absence
  (`TierTransitions.scala:30-38`).
- **Demotion is windowed, not single-round.** A Core peer is demoted only after it
  is absent from ALL of the most-recent `DemotionConsecutiveMisses` (= `3`,
  `TierTransitions.scala:82`) completed-round signer sets. The window includes the
  just-completed round, so a peer that signs the current round is never demoted.
  This sheds only a peer that has STOPPED signing, not one that is merely
  occasionally slow (`TierTransitions.scala:51-65`).
- **Demotion is gated on round completion.** Failed rounds (no `recentSigners[N]`
  entry) do not cascade-demote, so a single network flap during a round that would
  have failed anyway does not collapse the Core committee
  (`TierTransitions.scala:24-29`, `:108`).

`DemotionConsecutiveMisses` is a compiled-in constant, not a config slot
(`TierTransitions.scala:79-80`). It therefore changes only with a release artifact;
the release-version fence, rather than the advertised jar hash, prevents supported
mixed-version connections. A documented, accepted limitation: the window
holds SIGNER sets, not per-round eligibility, so the guarantee is "absent from the
last N signer sets" rather than the stronger "missed the last N rounds it was
eligible to sign"; the consequence is bounded and recoverable (Tier 1, re-promoted
by quality) and accepted for the crash-faulty testnet
(`TierTransitions.scala:67-78`).

---

## 7. Leader Eligibility

`LeaderEligibility.fromRecentSigners` (`LeaderEligibility.scala:27-69`) restricts
the leader pool to demonstrated-live recent signers, drawing only from the **Core**
committee (`GlobalSnapshotConsensusStateCreator.scala:623-643`,
`core = coreList = committees.core`). Tier 1 and Witness peers are never leader
candidates; Core is both the quorum denominator and the leader pool, so a peer that
loses its Core seat also loses its ability to lead.

Two successive filters, each with a safety fallback:

1. **Graduation filter** (`LeaderEligibility.scala:34-44`). Keep Core peers with
   `participated >= minParticipationObservations` AND `completed >= 1`. The
   `completed >= 1` clause ("kick-fast") closes the trap where chronic peers had
   accumulated `participated >= 5` but had never finalized a round and kept being
   elected leader and stalling -- a peer that has never finalized is not
   lead-eligible regardless of tenure, and one completed round as a follower
   restores eligibility (mirrors README sections 10's graduation-gated pool). Used
   only when `graduated.size >= minLeaderPoolSize`; otherwise the full Core list is
   the base (genesis / cold-start / solo-bootstrap tail, so view rotation stays
   meaningful).
2. **Recent-signer filter** (`LeaderEligibility.scala:46-59`). From the graduation
   base, keep peers present in ALL of the most-recent `DemotionConsecutiveMisses`
   signer sets, but only when the window is deep enough AND the resulting pool is at
   least `minLeaderPoolSize`; otherwise fall back to the graduation base.

Exclusions are reported as `NotGraduated` / `NotRecentSigner`
(`LeaderEligibility.scala:10-14`) and emitted as metrics. The resulting `leaderPool`
is then handed to the quality-weighted, view-rotating
`FacilitatorSelector.selectLeaderWeighted`
(`FacilitatorSelector.scala:238-275`), which applies a self-health and
completion-ratio tiering within the pool and uses rendezvous score plus
`viewNumber % pool.size` to rotate the leader (see [README section 10](README.md#10-leader-election--view-changes)).

---

## 8. Rewards and the committee

Rewards are distributed by the delegated-rewards path from the frozen round-start
signing committee. In the current tier-transition path that set is Core + Tier 1;
Witness is observation-only and is not seated. At and after the
`delegated-rewards-full-committee` ordinal, every seated Core and Tier-1 peer is a
validator recipient. Before that ordinal, the legacy score-qualified recipient rule
is retained strictly for historical replay. The payout formula is unchanged.

There is no per-seat reward rotation. An earlier bounded one-slot Tier-1 rotation
lane was removed. Reward breadth instead comes from retaining every otherwise
eligible signing lease as Core or Tier 1 and adding new leases through certified
open admission. Health/quality classification governs Core and leader eligibility;
it does not silently delete a Tier-1 signing/reward seat. A lease ends only through
the explicit eligibility, penalty/probation, withdrawal, or certified-eviction paths
described above.

---

## 9. How It Wires Into a Round

The per-round derivation in
`GlobalSnapshotConsensusStateCreator.scala` runs in this order. Currency L0 keeps
its existing bounded active-set policy because its configured finality threshold is
unanimity; broad retention there requires a separate design.

1. **Eligible base + subset select.** Apply the eligibility filtering pipeline and
   `facilitatorSelector.select` over the previous snapshot hash as entropy
   (`:288-291`).
2. **Controller inputs.** `controllerInputsWithFallback` derives
   scores/quality/tiers/chronicMisses from the signed `controllerEvidence` window,
   falling back to carried maps on an empty window (`:308-315`).
3. **Core classification and lease retention.** `chooseActive` classifies Core
   eligibility. `retainSelectedForSigning` retains all otherwise eligible selected peers and
   routes classifier exclusions to `nonCorePeers`.
4. **Tier partition.** `CommitteeBuilder.build` partitions the retained set into
   Core / Tier 1 / Witness and applies the Core floor and chronic-core ladder.
5. **Bounded signing-finality audit.** Before sending the first Facility, every node
   updates local actual-proof miss streaks for all auditable Core + Tier-1 signing
   peers. Current Core nodes audit the same entropy-ranked target. Under v35, a third
   consecutive local miss in the protocol-derived dead band may emit the existing
   eviction vote, but that evidence has no standalone removal authority: it must be
   paired with a Core-certified open ReadyAtTip admission in one exact N-to-N
   replacement.
6. **Leader selection.** `LeaderEligibility.fromRecentSigners` restricts the leader
   pool to graduated recent signers within Core (`:635-643`), then
   `selectLeaderWeighted` picks the view's leader.
7. **Round-start state.** The new `ConsensusState` carries the full active set as
   `Facilitators`, plus `coreFacilitators = CoreFacilitators(committees.core)` and
   `tier1Facilitators = Tier1Facilitators(committees.tier1)`
   (`:720-731`). The cert quorum reads `coreFacilitators`; the snapshot
   finalization threshold reads the full round-start committee.

On round completion, the outcome carries `peerTiers` (from
`TierTransitions.computeNextTiers`), `peerQuality`, and the appended
`controllerEvidence` entry forward into the next round, closing the loop. Because
every input to every step above is a consensus-agreed signed field or a deterministic
function of one, all honest nodes deciding the same round derive byte-identical
committees and the same leader.

See also: [README section 9](README.md#9-facilitator-selection) (the eligibility
filtering and rendezvous-hashing base), [README section 10](README.md#10-leader-election--view-changes)
(`selectLeaderWeighted` and view changes), and [README section 11](README.md#11-stall-detection--eviction)
(B1/B2 certificates, where the witness pool is consumed).
