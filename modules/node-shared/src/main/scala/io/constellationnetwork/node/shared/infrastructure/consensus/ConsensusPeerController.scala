package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.{SortedMap, SortedSet}

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
    // Bounded probation re-entry lane (see ActiveFacilitatorAdmission.fromRecentSigners). Default
    // 0 keeps the lane inert. Threaded from `ConsensusConfig.activeAdmissionMinProbationReentrySlots`
    // at the StateCreator construction sites.
    minProbationReentrySlots: Int = 0
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
        minProbationReentrySlots = clampNonNegative(minProbationReentrySlots)
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
        minProbationReentrySlots = 0
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

  final case class AdmissionInput(
    selected: List[PeerId],
    recentSigners: SortedMap[SnapshotOrdinal, SortedSet[PeerId]],
    peerQuality: Map[PeerId, (Int, Int)],
    activeScores: Map[PeerId, Int],
    minActiveSize: Int,
    targetActiveSize: Int,
    maxActiveSize: Int,
    minParticipationObservations: Int,
    minParticipationRatio: Double,
    config: Config
  )

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
      peerQuality = input.peerQuality,
      activeScores = input.activeScores,
      minActiveSize = input.minActiveSize,
      targetActiveSize = input.targetActiveSize,
      maxActiveSize = input.maxActiveSize,
      minParticipationObservations = input.minParticipationObservations,
      minParticipationRatio = input.minParticipationRatio,
      promoteThreshold = c.promoteThreshold,
      retainThreshold = c.retainThreshold,
      demoteThreshold = c.demoteThreshold,
      maxExpansionPerRound = c.maxExpansionPerRound,
      minProbationReentrySlots = c.minProbationReentrySlots
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
