package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.infrastructure.selfhealth.SelfHealthHint
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId

object ConsensusPeerController {

  final case class Config(
    promoteThreshold: Int,
    retainThreshold: Int,
    demoteThreshold: Int,
    maxScore: Int,
    signatureReward: Int,
    responderReward: Int,
    missedActivePenalty: Int,
    timeoutMissingPenalty: Int,
    evictedPenalty: Int,
    degradedPenalty: Int,
    criticalPenalty: Int,
    passiveDecay: Int,
    maxExpansionPerRound: Int,
    // Bounded sticky probation lane (see ActiveFacilitatorAdmission.fromRecentSigners). A peer
    // that signed the latest round keeps competing for a non-Core seat while below retain.
    // Default 0 keeps the lane inert.
    minProbationReentrySlots: Int = 0,
    // Recent-signer pool lookback depth (see ActiveFacilitatorAdmission.fromRecentSigners). Default
    // is the demotion-hysteresis constant (preserves the pre-change 3-ordinal lookback); threaded
    // from `ConsensusConfig.activeAdmissionRecentSignerWindow` at the StateCreator construction sites.
    recentSignerWindow: Int = TierTransitions.DemotionConsecutiveMisses
  ) {
    def bounded: Config =
      Config(
        promoteThreshold = clampNonNegative(promoteThreshold),
        retainThreshold = clampNonNegative(retainThreshold),
        demoteThreshold = clampNonNegative(demoteThreshold),
        maxScore = math.max(1, maxScore),
        signatureReward = clampNonNegative(signatureReward),
        responderReward = clampNonNegative(responderReward),
        missedActivePenalty = clampNonNegative(missedActivePenalty),
        timeoutMissingPenalty = clampNonNegative(timeoutMissingPenalty),
        evictedPenalty = clampNonNegative(evictedPenalty),
        degradedPenalty = clampNonNegative(degradedPenalty),
        criticalPenalty = clampNonNegative(criticalPenalty),
        passiveDecay = clampNonNegative(passiveDecay),
        maxExpansionPerRound = clampNonNegative(maxExpansionPerRound),
        minProbationReentrySlots = clampNonNegative(minProbationReentrySlots),
        recentSignerWindow = math.max(TierTransitions.DemotionConsecutiveMisses, recentSignerWindow)
      )
  }

  object Config {
    val default: Config =
      Config(
        promoteThreshold = 100,
        retainThreshold = 70,
        demoteThreshold = 40,
        maxScore = 150,
        signatureReward = 20,
        responderReward = 5,
        missedActivePenalty = 15,
        timeoutMissingPenalty = 10,
        evictedPenalty = 40,
        degradedPenalty = 5,
        criticalPenalty = 20,
        passiveDecay = 1,
        maxExpansionPerRound = 1,
        minProbationReentrySlots = 0,
        recentSignerWindow = TierTransitions.DemotionConsecutiveMisses
      )
  }

  final case class RoundEvidence(
    roundStart: Set[PeerId],
    completed: Set[PeerId],
    responders: Set[PeerId],
    timeoutVoters: Set[PeerId] = Set.empty,
    evicted: Set[PeerId],
    observedSelfHealth: SortedMap[PeerId, SelfHealthHint]
  )

  final case class AdmissionSizing(
    emergencyBypassFloor: Int,
    targetActiveSize: Int,
    maxActiveSize: Int
  )

  object AdmissionSizing {

    /** Resolves the active-admission sizing policy once for both GL0 and currency L0.
      *
      * `emergencyBypassFloor` controls only the bootstrap/collapse escape hatch. It is deliberately distinct from the normal Core committee
      * size and the active-set growth target.
      */
    def from(config: ConsensusConfig, coreCommitteeSize: Int, selectedSize: Int): AdmissionSizing =
      AdmissionSizing(
        // Floored to 1: a non-positive configured floor would arm the recent-signer gate with an
        // EMPTY retained pool, sending every candidate through the (non-Core) probation lane and
        // collapsing the committee to core=0 (the ColdStartRound5ReproSuite crash shape).
        emergencyBypassFloor = math.max(1, config.activeFacilitatorFloor),
        targetActiveSize = config.activeFacilitatorTarget.getOrElse(coreCommitteeSize),
        maxActiveSize = config.activeFacilitatorMax
          .getOrElse(config.maxFacilitatorCount.map(_.value).getOrElse(selectedSize))
      )
  }

  final case class AdmissionInput(
    selected: List[PeerId],
    recentSigners: SortedMap[SnapshotOrdinal, SortedSet[PeerId]],
    latestRoundStartFacilitators: Set[PeerId],
    peerQuality: Map[PeerId, (Int, Int)],
    activeScores: Map[PeerId, Int],
    sizing: AdmissionSizing,
    minParticipationObservations: Int,
    minParticipationRatio: Double,
    config: Config
  )

  /** Separates signing-committee retention from Core eligibility.
    *
    * `ActiveFacilitatorAdmission` historically used its score/recent-signer result as the complete signing committee. That made a
    * classification miss delete a validator seat and its reward eligibility, even though the tiered design already has Tier 1 for peers
    * that should keep signing without entering the Core liveness denominator.
    *
    * A deterministically selected peer keeps its signing lease. Peers outside the controller's Core-eligible result, plus its explicit
    * probation cohort, are forced to Tier 1 when `CommitteeBuilder` partitions the round. In particular, the controller's chronic-miss
    * signal is derived from the leader's early Facility responder set, not from canonical snapshot-proof participation; it is therefore a
    * valid Core/leader classification input but not authority to delete a signing/reward seat. Upstream collateral, withdrawal, penalty,
    * probation, and certified-eviction rules still decide which peers reach `selected`. Witnesses are still removed by the builder; this
    * helper does not turn a Witness into a signing peer.
    */
  final case class SigningMembership(
    retained: List[PeerId],
    nonCore: Set[PeerId]
  )

  def retainSelectedForSigning(
    selected: List[PeerId],
    classification: ActiveFacilitatorAdmission.Result
  ): SigningMembership = {
    val retained = selected.distinct
    val retainedSet = retained.toSet
    val coreEligible = classification.active.toSet.intersect(retainedSet)
    val probation = classification.probationAdmitted.toSet.intersect(retainedSet)

    SigningMembership(
      retained = retained,
      nonCore = (retainedSet -- coreEligible) ++ probation
    )
  }

  def advanceScores(
    prior: SortedMap[PeerId, Int],
    evidence: RoundEvidence,
    config: Config
  ): SortedMap[PeerId, Int] = {
    val c = config.bounded
    val keys =
      prior.keySet |
        evidence.roundStart |
        evidence.completed |
        evidence.responders |
        evidence.evicted |
        evidence.observedSelfHealth.keySet

    SortedMap.from(
      keys.iterator.flatMap { pid =>
        val delta =
          -c.passiveDecay +
            (if (evidence.completed.contains(pid)) c.signatureReward else 0) +
            (if (evidence.responders.contains(pid)) c.responderReward else 0) -
            (if (evidence.roundStart.contains(pid) && !evidence.responders.contains(pid)) c.missedActivePenalty else 0) -
            (if (evidence.timeoutVoters.nonEmpty && evidence.roundStart.contains(pid) && !evidence.timeoutVoters.contains(pid))
               c.timeoutMissingPenalty
             else 0) -
            (if (evidence.evicted.contains(pid)) c.evictedPenalty else 0) -
            selfHealthPenalty(evidence.observedSelfHealth.get(pid), c)
        val next = clamp(prior.getOrElse(pid, 0) + delta, 0, c.maxScore)
        Option.when(next > 0)(pid -> next)
      }
    )
  }

  def chooseActive(input: AdmissionInput): ActiveFacilitatorAdmission.Result = {
    val c = input.config.bounded
    ActiveFacilitatorAdmission.fromRecentSigners(
      selected = input.selected,
      recentSigners = input.recentSigners,
      latestRoundStartFacilitators = input.latestRoundStartFacilitators,
      peerQuality = input.peerQuality,
      activeScores = input.activeScores,
      minActiveSize = input.sizing.emergencyBypassFloor,
      targetActiveSize = input.sizing.targetActiveSize,
      maxActiveSize = input.sizing.maxActiveSize,
      minParticipationObservations = input.minParticipationObservations,
      minParticipationRatio = input.minParticipationRatio,
      promoteThreshold = c.promoteThreshold,
      retainThreshold = c.retainThreshold,
      demoteThreshold = c.demoteThreshold,
      maxExpansionPerRound = c.maxExpansionPerRound,
      minProbationReentrySlots = c.minProbationReentrySlots,
      recentSignerWindow = c.recentSignerWindow
    )
  }

  def canonicalFacilitatorBase(
    parentFacilitators: List[PeerId],
    seedlistPeerIds: List[PeerId]
  ): List[PeerId] = {
    val allowed = (peerId: PeerId) => seedlistPeerIds.isEmpty || seedlistPeerIds.contains(peerId)

    parentFacilitators.filter(allowed).distinct
  }

  def applyCertifiedAdmissions(parentFacilitators: List[PeerId], admittedPeers: Iterable[PeerId]): List[PeerId] = {
    val parent = parentFacilitators.distinct
    val parentSet = parent.toSet
    // Preserve parent order for committee stability; append only newly certified admissions in
    // stable PeerId order so Set-backed certificate collections cannot perturb the next base.
    val admitted = admittedPeers.toList.distinct.sorted.filterNot(parentSet.contains)

    parent ++ admitted
  }

  /** Derive the next-round signing roster across the legacy/v35 boundary.
    *
    * Legacy `removedFacilitators` is operational evidence carried beside the roster; rc.7 deliberately does not consume it as a new
    * membership deletion. Only an eviction inside a certified v35 ProposalValue has N+1 authority. Encoding that distinction as `None`
    * versus `Some` keeps the mirrored DAG and Currency advancers on one rule.
    */
  def applyNextRoundCertifiedMembership(
    roundStartFacilitators: List[PeerId],
    admittedPeers: Iterable[PeerId],
    certifiedEvictedPeers: Option[Iterable[PeerId]]
  ): List[PeerId] = {
    val evicted = certifiedEvictedPeers.fold(Set.empty[PeerId])(_.toSet)
    val retained = roundStartFacilitators.filterNot(evicted.contains)

    applyCertifiedAdmissions(retained, admittedPeers)
  }

  private def selfHealthPenalty(hint: Option[SelfHealthHint], config: Config): Int =
    hint match {
      case Some(SelfHealthHint.Critical) => config.criticalPenalty
      case Some(SelfHealthHint.Degraded) => config.degradedPenalty
      case _                             => 0
    }

  private def clampNonNegative(value: Int): Int = math.max(0, value)

  private def clamp(value: Int, min: Int, max: Int): Int =
    math.max(min, math.min(max, value))
}
