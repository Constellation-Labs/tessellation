package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.Show
import cats.syntax.show._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.node.shared.infrastructure.consensus.ControllerEvidenceDerivation
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.node.shared.infrastructure.selfhealth.SelfHealthHint
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import monocle.Lens
import monocle.macros.GenLens

object schema {

  /** Consensus status phases for the Global L0 snapshot consensus state machine.
    *
    * {{{
    * CollectingFacilities → CollectingProposals → CollectingSignatures → Finished
    * }}}
    *
    * Each phase collects peer declarations (facilities, proposals, signatures) until quorum is reached, then advances to the next phase.
    * The `Finished` state carries the signed artifact and context forward as `lastOutcome` for the next consensus round.
    */
  @derive(eqv)
  sealed trait GlobalConsensusStep

  object GlobalConsensusStep {
    implicit val show: Show[GlobalConsensusStep] = Show.show {
      case CollectingFacilities(maybeTrigger, facilitatorsHash, lastSnapshotHash) =>
        s"CollectingFacilities{maybeTrigger=${maybeTrigger.show}, facilitatorsHash=${facilitatorsHash.show}, lastSnapshotHash=${lastSnapshotHash.show}"
      case CollectingProposals(
            majorityTrigger,
            proposalArtifactInfo,
            candidates,
            facilitatorsHash,
            lastSnapshotHash,
            observedResponders,
            observedSelfHealth
          ) =>
        s"CollectingProposals{majorityTrigger=${majorityTrigger.show}, proposalArtifactInfo=${proposalArtifactInfo.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}, lastSnapshotHash=${lastSnapshotHash.show}, observedRespondersCount=${observedResponders.size}, observedSelfHealthCount=${observedSelfHealth.size}}"
      case CollectingSignatures(majorityArtifactInfo, majorityTrigger, candidates, facilitatorsHash, lastSnapshotHash) =>
        s"CollectingSignatures{majorityArtifactInfo=${majorityArtifactInfo.show}, ${majorityTrigger.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}, lastSnapshotHash=${lastSnapshotHash.show}}"
      case Finished(_, _, majorityTrigger, candidates, facilitatorsHash, snapshotHash) =>
        s"Finished{majorityTrigger=${majorityTrigger.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}, snapshotHash=${snapshotHash.show}"
    }
  }

  final case class CollectingFacilities(
    maybeTrigger: Option[ConsensusTrigger],
    facilitatorsHash: Hash,
    lastSnapshotHash: Hash
  ) extends GlobalConsensusStep

  final case class CollectingProposals(
    majorityTrigger: ConsensusTrigger,
    proposalArtifactInfo: ArtifactInfo[GlobalSnapshotArtifact, GlobalSnapshotContext],
    candidates: Candidates,
    facilitatorsHash: Hash,
    lastSnapshotHash: Hash,
    // v7 (codex turn 2 fix #2): leader's observedResponders frozen at proposal-build time
    // so leader re-spread (advancer:889 → spreadProposal:2098) reads from this immutable
    // status field instead of recomputing from current resources. Without this, every
    // re-spread could produce a different observedResponders set, breaking determinism.
    observedResponders: List[PeerId],
    // v15: same byte-identical-re-spread rationale as observedResponders. The aggregated
    // selfHealth map is frozen here at proposal-build time so any retransmit reproduces the
    // original Proposal payload exactly. SortedMap so circe encodes in deterministic key order.
    observedSelfHealth: SortedMap[PeerId, SelfHealthHint]
  ) extends GlobalConsensusStep

  final case class CollectingSignatures(
    majorityArtifactInfo: ArtifactInfo[GlobalSnapshotArtifact, GlobalSnapshotContext],
    majorityTrigger: ConsensusTrigger,
    candidates: Candidates,
    facilitatorsHash: Hash,
    lastSnapshotHash: Hash
  ) extends GlobalConsensusStep

  @derive(encoder, decoder, eqv)
  final case class Finished(
    signedMajorityArtifact: Signed[GlobalSnapshotArtifact],
    context: GlobalSnapshotContext,
    majorityTrigger: ConsensusTrigger,
    candidates: Candidates,
    facilitatorsHash: Hash,
    snapshotHash: Hash
  ) extends GlobalConsensusStep

  /** Outcome of a completed consensus round, carried forward as `lastOutcome` into the next round.
    *
    * `removalPenalties` uses `SortedMap` to ensure deterministic iteration when computing penalty decrements and filtering penalized peers
    * in the next round.
    *
    * `peerQuality` tracks consensus-agreed quality scores: `(roundsCompleted, roundsParticipated)` per peer. Because all nodes in a round
    * agree on the same facilitator list, removals, and withdrawals, these counters are deterministic across the network — enabling
    * quality-weighted leader selection without local score divergence.
    */
  @derive(encoder, decoder, eqv)
  final case class GlobalConsensusOutcome(
    key: GlobalSnapshotKey,
    facilitators: Facilitators,
    removedFacilitators: RemovedFacilitators,
    withdrawnFacilitators: WithdrawnFacilitators,
    eligibleFacilitators: EligibleFacilitators,
    finished: Finished,
    removalPenalties: SortedMap[PeerId, Int] = SortedMap.empty,
    deferralCountdown: SortedMap[PeerId, Int] = SortedMap.empty,
    peerQuality: SortedMap[PeerId, (Int, Int)] = SortedMap.empty,
    // Lifetime count of times this peer was evicted/removed. Used to scale the
    // `removalPenaltyRounds` exponentially so repeat offenders get progressively
    // longer bans. Persisted in the signed outcome → consensus-agreed → all nodes
    // compute the same penalty. Defaults to empty so old outcomes roll over cleanly.
    cumulativeMissCounts: SortedMap[PeerId, Long] = SortedMap.empty,
    // Sliding window of (ordinal -> proofs.size) for the last ~10 ordinals. Used to
    // classify whether the chain has completed initial bootstrap (any entry >=
    // bootstrapCompleteProofsThreshold => post-bootstrap). While in bootstrap,
    // penalty accrual is suppressed so slow peers aren't ejected during the
    // solo->multi transition. Chain-derived: seeded from `lastNGlobalSnapshotStorage`
    // on rollback init, rolls forward one entry per outcome. Non-monotonic by
    // design — if the cluster degrades to solo for > lookback ordinals, warmup
    // re-engages, which is appropriate for re-stabilizing after mass peer loss.
    recentProofSizes: SortedMap[SnapshotOrdinal, Int] = SortedMap.empty,
    // B2 re-admission gate: peers whose `removalPenalty` expired enter this map
    // at `readmissionProbationRounds` and count down one per finished round. While
    // the entry exists, the peer is excluded from both `fullBase` and
    // `potentiallyCompeting` in state creation (non-bypassable, even on the
    // withoutPenaltiesOnly path). Removal from this map happens when the advancer
    // observes the peer in a quorum-certified AdmissionCertificate embedded in a
    // proposal (re-admitted via consensus-witnessed current-tip participation).
    // Consensus-agreed → deterministic across all peers.
    readmissionCountdown: SortedMap[PeerId, Int] = SortedMap.empty,
    // Self-health throttle: each peer's last-known `SelfHealthHint`
    // copied from the accepted Proposal's `observedSelfHealth`. Read by the next round's
    // `selectLeaderWeighted` to demote Degraded peers to tier 1 and Critical peers to
    // tier 2 (strong demote, not hard exclude -- keeps liveness if all peers report Critical).
    // Peers absent from the map default to `Healthy` at read time. Not persisted into the
    // snapshot operational state (see docs/consensus/self-health-throttle.md, open decision
    // 4): a freshly-restarted cluster picks leaders without hints until the first round of
    // facilities arrives.
    peerSelfHealth: SortedMap[PeerId, SelfHealthHint] = SortedMap.empty,
    // Cumulative per-peer count of view changes this peer caused as a
    // failed leader-of-the-view. Derived at round finalization by the StateAdvancer from
    // (roundStartFacilitators, entropy, finalView, prior peerQuality, prior peerSelfHealth,
    // prior peerViewChanges) -- all consensus-agreed inputs -- by recomputing
    // selectLeaderWeighted at each view v in [0, state.viewNumber) and crediting the
    // resulting peer with one view-change-caused. Used by the next round's
    // selectLeaderWeighted to compute a fork-safe integer qualityScore =
    // completed * (participated - viewChangesCaused) / participated^2 and hard-exclude
    // peers below the configured floor. Default empty: pre-v16 outcomes decode with no
    // view-change history (matches v15 peerSelfHealth pattern). Persisted via v20/v21
    // PerPeerOperationalRecord so the chronic-leader filter survives cold-restart.
    peerViewChanges: SortedMap[PeerId, Long] = SortedMap.empty,
    // v22: rolling K-round signer-set window of (ordinal -> proofs-signer-set) for the
    // last `tighteningWindow` outcomes. Repopulated every round (the just-completed
    // round's signer set is appended) and used as the INPUT to the tier-demotion
    // hysteresis: `TierTransitions.computeNextTiers` reads this window, and a Core peer
    // absent from the most-recent `TierTransitions.DemotionConsecutiveMisses` signer
    // sets is demoted to Tier 1. Window grows by one entry per round; entries older than
    // K are dropped at outcome finalization. Each entry is the just-completed round's
    // signer set, byte-identically derived on every honest node since
    // `completedFacilitators` (roundStartFacilitators minus evictedPeers) is canonical
    // round-start state. Persisted via toOperationalState below; survives cold-restart
    // so a freshly-rebooted cluster doesn't lose K rounds of demotion history. Default
    // empty: outcomes that pre-date the window have no signer history, treated as a
    // bootstrap window (the window-deep-enough guard in computeNextTiers suppresses
    // demotion until the window fills).
    recentSigners: SortedMap[SnapshotOrdinal, SortedSet[PeerId]] = SortedMap.empty,
    // v19/v22 multi-committee tier classification per peer. Computed at round-finalize by
    // `TierTransitions.computeNextTiers(priorTiers, roundStartFacilitators,
    // recentSignersWindow, roundCompleted)`, which per peer demotes a Core peer that was in
    // roundStart but absent from the most-recent `DemotionConsecutiveMisses` signer sets.
    // Consensus-agreed: all inputs are deterministic outcome fields, so every honest node
    // produces the byte-identical map.
    //
    // Tier 2 (Core): full facilitator, in the LIVENESS quorum.
    // Tier 1: witness-eligible, not in the LIVENESS quorum.
    // Tier 0 (Witness): open membership, observation only.
    //
    // Persisted via `toOperationalState` -> `PerPeerOperationalRecord.tier`; restored on
    // cold-restart at Main.scala. Default empty: pre-v19 outcomes have no tier history. At
    // committee-derivation time `CommitteeBuilder` defaults an absent peer to Tier 1 (not
    // Core) unless `peerQuality` proves it above the ratio bar -- the replacement for the
    // original "everyone defaults to Core" bootstrap that let unclassified peers wedge the
    // cluster.
    peerTiers: SortedMap[PeerId, Int] = SortedMap.empty,
    // v27 consensus peer controller score: bounded integral state for admitting peers into
    // rewards-affecting active roles. Updated only from finalized evidence and persisted via
    // peerHistory so a restart cannot promote a no-evidence peer.
    activeAdmissionScores: SortedMap[PeerId, Int] = SortedMap.empty,
    // v28 controller evidence: voters from the TimeoutCertificate embedded in the
    // accepted proposal for the just-finalized round. This field is not a long-lived
    // history window; the bounded integral `activeAdmissionScores` is the durable
    // controller state. Persisting this one-round set makes the -D timeout-missing
    // penalty an outcome-derived fact instead of a local timeout-cache observation.
    lastTimeoutCertificateVoters: SortedSet[PeerId] = SortedSet.empty,
    // v19 phase 2 view-from-time anchor: sliding window of (ordinal -> consensusEndTime).
    // `consensusEndTime` = `max(median(Facility.proposerClockMs), parent.consensusEndTime + 1)`,
    // computed from the consensus-agreed Facility set at outcome finalization. Bounded by
    // the same `tighteningWindow` as `recentSigners`. Consumed by the next round's
    // view-from-time mechanism. Persisted via `toOperationalState` (Option-wrapped at the
    // snapshot boundary for derevo back-compat with pre-v19 snapshots). Default empty:
    // window has not yet been populated; view derivation falls back to phase 1
    // `viewChangeVotes.maxToView`.
    recentRoundEndTimes: SortedMap[SnapshotOrdinal, Long] = SortedMap.empty,
    // Controller evidence stage 1 (write-only, no consumer yet): bounded window of
    // (ordinal -> ControllerEvidenceEntry) recording the canonical facts of each finalized
    // round -- round-start committee, completed signer set, certified timeout voters,
    // certified admissions, certified evictions. Bounded by the same `tighteningWindow`
    // as `recentSigners`. This is the signed-chain evidence that
    // `ControllerEvidenceDerivation` recomputes per-peer scores / tiers / quality from,
    // replacing (at stage 4) the carried maps above that a divergent restart seed can
    // poison (the alpha.92/129/147 wedge class). Option-wrapped for circe back-compat:
    // outcomes written before this field decode the missing key to None.
    controllerEvidence: Option[SortedMap[SnapshotOrdinal, ControllerEvidenceEntry]] = None,
    // Controller evidence stage 3 (write-only, no consumer yet): cert-anchored absolute
    // penalty horizon per peer. An EvictionCertificate applied at ordinal N writes
    // `target -> N + penaltyDurationOrdinals`; an AdmissionCertificate clears the entry;
    // expired entries (<= current key) are dropped at finalization. Pure ordinal
    // comparisons only -- no per-round countdown mutation that a restart could observe
    // half-applied. Option-wrapped for circe back-compat like `controllerEvidence`.
    penaltyUntil: Option[SortedMap[PeerId, SnapshotOrdinal]] = None
  ) {
    def eligibleOrFacilitators: List[PeerId] =
      if (eligibleFacilitators.value.nonEmpty) eligibleFacilitators.value
      else facilitators.value

    // v20: package the consensus-derived peer-behavior counters for persistence
    // on the next round's incremental snapshot.
    //
    // This is the FULL operational state (written to the snapshot / PeerHistorySidecar
    // after finalization). The signed-artifact path is narrower: `signedArtifactPeerHistory`
    // below carries the deterministic chain-derived fields ONLY (recentProofSizes,
    // recentSigners, controllerEvidence, penaltyUntil -- fully sorted, byte-identical
    // across honest nodes) and keeps the locally-divergent `perPeer` /
    // `recentRoundEndTimes` out of the proposal-critical bytes.
    //
    // v21/v27 layout: peer-keyed dimensions collapsed into a single map keyed by
    // PeerId so each id appears once. The union of keys across the peer-keyed
    // source maps becomes the per-peer map's key set;
    // absent peers contribute `PerPeerOperationalRecord.empty` semantics on the consumer side.
    def toOperationalState: ConsensusOperationalState = {
      val keys: Set[PeerId] =
        peerQuality.keySet |
          removalPenalties.keySet |
          cumulativeMissCounts.keySet |
          readmissionCountdown.keySet |
          deferralCountdown.keySet |
          peerViewChanges.keySet |
          peerTiers.keySet |
          activeAdmissionScores.keySet
      val perPeer: SortedMap[PeerId, PerPeerOperationalRecord] =
        SortedMap.from(
          keys.iterator.map { pid =>
            pid -> PerPeerOperationalRecord(
              quality = peerQuality.getOrElse(pid, (0, 0)),
              removalPenalty = removalPenalties.getOrElse(pid, 0),
              cumulativeMissCount = cumulativeMissCounts.getOrElse(pid, 0L),
              readmissionCountdown = readmissionCountdown.getOrElse(pid, 0),
              deferralCountdown = deferralCountdown.getOrElse(pid, 0),
              // v16: Option-wrap so absent peers / pre-v16 readers see no key under
              // dropNullValues=true. Some(0) would render as "0" and break byte-stable
              // back-compat; only emit Some when there is actual history to persist.
              viewChangesCaused = peerViewChanges.get(pid).filter(_ > 0L),
              // v19: only emit Some when there is an actual tier classification on this
              // peer. Absent peers / no-history readers see no key under dropNullValues=true,
              // and CommitteeBuilder defaults `None` to bootstrap-Tier-2 at consume time.
              tier = peerTiers.get(pid),
              activeAdmissionScore = activeAdmissionScores.get(pid).filter(_ > 0)
            )
          }
        )
      ConsensusOperationalState(
        perPeer = perPeer,
        recentProofSizes = recentProofSizes,
        // Emit `Some(nonEmptyMap)` so `dropNullValues=true` keeps the field out of
        // byte-stable encodings written before this field existed; emit `None` while
        // the window is empty (bootstrap, rollback to an old snapshot) so the restored
        // window stays empty and the tier-demotion hysteresis stays in its bootstrap
        // (no-demote) regime until the window refills.
        recentSigners = if (recentSigners.nonEmpty) Some(recentSigners) else None,
        // v19 phase 2: same Option-wrap pattern as recentSigners. None at the snapshot
        // boundary means the cluster has not yet produced a round whose Facility set
        // carried enough `proposerClockMs` values to compute the median (bootstrap or
        // partial-deploy); view derivation falls back to phase 1 vote-driven tick.
        recentRoundEndTimes = if (recentRoundEndTimes.nonEmpty) Some(recentRoundEndTimes) else None,
        // Stage 4: persist the evidence window + cert-anchored penalties so a cold restart
        // re-seeds them from the sidecar / snapshot.peerHistory and the evidence-derived
        // controller state survives the restart boundary. Same non-empty Option-wrap as
        // recentSigners (the outcome already holds None-while-empty; filter defends the
        // Some(empty) case so dropNullValues byte-stability is preserved).
        controllerEvidence = controllerEvidence.filter(_.nonEmpty),
        penaltyUntil = penaltyUntil.filter(_.nonEmpty)
      )
    }

    // Stage 4: the peerHistory payload packed into SIGNED artifact bytes at proposal build
    // and validateArtifact re-execution. Evidence-only: `perPeer` and `recentRoundEndTimes`
    // (the locally-divergent fields behind the alpha.92/129/147 wedges) are excluded;
    // delegation to the shared helper keeps the dag-l0 / currency-l0 signed subsets from
    // drifting apart.
    def signedArtifactPeerHistory: ConsensusOperationalState =
      ControllerEvidenceDerivation.signedArtifactOperationalState(
        recentProofSizes = recentProofSizes,
        recentSigners = recentSigners,
        controllerEvidence = controllerEvidence,
        penaltyUntil = penaltyUntil
      )
  }

  @derive(encoder, decoder, eqv, show)
  sealed trait GlobalConsensusKind

  object GlobalConsensusKind {
    case object Facility extends GlobalConsensusKind

    case object Proposal extends GlobalConsensusKind

    case object Signature extends GlobalConsensusKind
  }

  object GlobalConsensusOutcome {
    implicit val _artifact: Lens[GlobalConsensusOutcome, Signed[GlobalSnapshotArtifact]] =
      GenLens[GlobalConsensusOutcome](_.finished.signedMajorityArtifact)
    implicit val _context: Lens[GlobalConsensusOutcome, GlobalSnapshotContext] =
      GenLens[GlobalConsensusOutcome](_.finished.context)
    implicit val _facilitators: Lens[GlobalConsensusOutcome, Facilitators] =
      GenLens[GlobalConsensusOutcome](_.facilitators)
    implicit val _key: Lens[GlobalConsensusOutcome, GlobalSnapshotKey] =
      GenLens[GlobalConsensusOutcome](_.key)
    implicit val _trigger: Lens[GlobalConsensusOutcome, ConsensusTrigger] =
      GenLens[GlobalConsensusOutcome](_.finished.majorityTrigger)
  }
}
