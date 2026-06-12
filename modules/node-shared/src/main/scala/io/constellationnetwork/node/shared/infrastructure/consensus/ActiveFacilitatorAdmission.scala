package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId

object ActiveFacilitatorAdmission {
  private val ParticipationRatioScale = 1000000L

  private def minParticipationRatioScaled(minParticipationRatio: Double): Long =
    math.round(minParticipationRatio * ParticipationRatioScale)

  private def participationRatioScaled(completed: Int, participated: Int): Long =
    if (participated <= 0) 0L else completed.toLong * ParticipationRatioScale / participated.toLong

  private def meetsParticipationRatio(completed: Int, participated: Int, minParticipationRatioScaled: Long): Boolean =
    participated > 0 && completed.toLong * ParticipationRatioScale >= minParticipationRatioScaled * participated.toLong

  sealed abstract class ExclusionReason(val label: String)
  object ExclusionReason {
    case object NotRecentSigner extends ExclusionReason("not_recent_signer")
    case object QualityBelowThreshold extends ExclusionReason("quality_below_threshold")
    case object ScoreBelowPromoteThreshold extends ExclusionReason("score_below_promote_threshold")
    case object ScoreBelowRetainThreshold extends ExclusionReason("score_below_retain_threshold")
    case object ScoreBelowDemoteThreshold extends ExclusionReason("score_below_demote_threshold")
    case object BeyondTarget extends ExclusionReason("beyond_target")
    case object CertifiedTimeoutMissing extends ExclusionReason("certified_timeout_missing")
  }

  final case class Exclusion(peerId: PeerId, reason: ExclusionReason)

  final case class Result(
    active: List[PeerId],
    exclusions: List[Exclusion],
    recentSignerPoolSize: Int,
    candidateSize: Int,
    targetSize: Int,
    promotedCandidateSize: Int,
    scoreExcludedSize: Int,
    qualityExcludedSize: Int,
    demotedRecentSignerSize: Int,
    belowRetainRecentSignerSize: Int,
    expansionAdmittedSize: Int,
    reserveAdmitted: List[PeerId],
    reserveAdmittedSize: Int,
    probationAdmitted: List[PeerId],
    probationAdmittedSize: Int,
    recentSignerMinCount: Int,
    recentSignerMaxCount: Int,
    recentWindowSize: Int,
    recentFilterApplied: Boolean
  )

  def fromRecentSigners(
    selected: List[PeerId],
    recentSigners: SortedMap[SnapshotOrdinal, SortedSet[PeerId]],
    peerQuality: Map[PeerId, (Int, Int)],
    activeScores: Map[PeerId, Int] = Map.empty,
    minActiveSize: Int,
    targetActiveSize: Int,
    maxActiveSize: Int,
    minParticipationObservations: Int,
    minParticipationRatio: Double,
    promoteThreshold: Int = 100,
    retainThreshold: Int = 70,
    demoteThreshold: Int = 40,
    maxExpansionPerRound: Int = Int.MaxValue
  ): Result = {
    val minRatioScaled = minParticipationRatioScaled(minParticipationRatio)

    def qualityRank(pid: PeerId): (Int, Long, Int, String) =
      peerQuality.get(pid) match {
        case Some((completed, participated)) if participated > 0 =>
          val ratioScaled = participationRatioScaled(completed, participated)
          val qualityClass =
            if (participated >= minParticipationObservations && !meetsParticipationRatio(completed, participated, minRatioScaled)) 2
            else if (participated >= minParticipationObservations) 0
            else 1

          (qualityClass, -ratioScaled, -completed, pid.value.value)
        case _ =>
          (1, 0, 0, pid.value.value)
      }

    def isQualityExcluded(pid: PeerId): Boolean =
      peerQuality.get(pid).exists {
        case (completed, participated) if participated >= minParticipationObservations && participated > 0 =>
          !meetsParticipationRatio(completed, participated, minRatioScaled)
        case _ => false
      }

    val recentSets = recentSigners.values.toList.takeRight(TierTransitions.DemotionConsecutiveMisses)
    def recentSignerCount(pid: PeerId): Int = recentSets.count(_.contains(pid))
    val scoreHistoryAvailable = activeScores.nonEmpty
    def bootstrapScore(pid: PeerId): Int =
      peerQuality.get(pid) match {
        case Some((completed, participated))
            if participated >= minParticipationObservations && participated > 0 &&
              meetsParticipationRatio(completed, participated, minRatioScaled) =>
          promoteThreshold
        case _ if recentSignerCount(pid) > 0 => retainThreshold
        case _                               => 0
      }
    def scoreOf(pid: PeerId): Int = activeScores.getOrElse(pid, bootstrapScore(pid))
    def recentSignerRank(pid: PeerId): (Int, Int, Int, Long, Int, String) = {
      val (qualityClass, ratioRank, completedRank, peerIdRank) = qualityRank(pid)
      (-recentSignerCount(pid), -scoreOf(pid), qualityClass, ratioRank, completedRank, peerIdRank)
    }

    val recentWindowDeepEnough = recentSets.sizeIs >= TierTransitions.DemotionConsecutiveMisses
    val (demotedRecentSigners, retainedOrSoftExcludedRecentSigners) =
      if (recentWindowDeepEnough)
        selected.filter(pid => recentSets.exists(_.contains(pid))).partition(pid => scoreHistoryAvailable && scoreOf(pid) < demoteThreshold)
      else
        (List.empty[PeerId], selected)
    val (belowRetainRecentSigners, retainedRecentSignerCandidates) =
      if (recentWindowDeepEnough)
        retainedOrSoftExcludedRecentSigners.partition(pid => scoreHistoryAvailable && scoreOf(pid) < retainThreshold)
      else
        (List.empty[PeerId], retainedOrSoftExcludedRecentSigners)
    val uncappedRecentSignerPool = retainedRecentSignerCandidates.sortBy(recentSignerRank)
    val configuredMax = math.max(minActiveSize, maxActiveSize)
    val target =
      math.min(
        selected.size,
        math.min(configuredMax, List(minActiveSize, targetActiveSize, uncappedRecentSignerPool.size).max)
      )
    val recentSignerPool = uncappedRecentSignerPool.take(target)
    val recentSignerSet = recentSignerPool.toSet
    val recentScoreExcludedSet = (demotedRecentSigners ++ belowRetainRecentSigners).toSet
    val admittedRecentSignerCounts = recentSignerPool.map(recentSignerCount)
    val recentSignerMinCount = admittedRecentSignerCounts.minOption.getOrElse(0)
    val recentSignerMaxCount = admittedRecentSignerCounts.maxOption.getOrElse(0)
    val excludedRecentOverflow =
      if (uncappedRecentSignerPool.size > recentSignerPool.size)
        uncappedRecentSignerPool.drop(recentSignerPool.size).map(Exclusion(_, ExclusionReason.BeyondTarget))
      else
        List.empty
    val useRecentSignerPool = recentWindowDeepEnough && uncappedRecentSignerPool.size >= minActiveSize

    val expansionBase =
      if (useRecentSignerPool)
        selected.filterNot(pid => recentSignerSet.contains(pid) || recentScoreExcludedSet.contains(pid))
      else
        List.empty
    val (qualityExcluded, qualityExpansionCandidates) = expansionBase.partition(isQualityExcluded)
    val (scoreExcluded, promotedExpansionCandidates) = qualityExpansionCandidates.partition(pid => scoreOf(pid) < promoteThreshold)
    def expansionRank(pid: PeerId): (Int, Int, Long, Int, String) = {
      val (qualityClass, ratioRank, completedRank, peerIdRank) = qualityRank(pid)
      (-scoreOf(pid), qualityClass, ratioRank, completedRank, peerIdRank)
    }
    def probationRank(pid: PeerId): (Int, Long, Int, String) = {
      val (qualityClass, ratioRank, completedRank, peerIdRank) = qualityRank(pid)
      (qualityClass, ratioRank, completedRank, peerIdRank)
    }
    val sortedPromotedExpansionCandidates = promotedExpansionCandidates.sortBy(expansionRank)
    val expansionSlots = math.max(0, target - recentSignerPool.size)
    val expansionLimit = math.min(expansionSlots, maxExpansionPerRound)
    val promotedAdmitted = sortedPromotedExpansionCandidates.take(expansionLimit)
    val probationSlots = math.max(0, expansionLimit - promotedAdmitted.size)
    val probationAdmitted = scoreExcluded.sortBy(probationRank).take(probationSlots)
    val expansionAdmitted = promotedAdmitted ++ probationAdmitted
    val expansionAdmittedSet = expansionAdmitted.toSet
    val reserveSlots = math.max(0, target - recentSignerPool.size - expansionAdmitted.size)
    val reserveAdmitted = sortedPromotedExpansionCandidates.filterNot(expansionAdmittedSet.contains).take(reserveSlots)
    val reserveAdmittedSet = reserveAdmitted.toSet
    val beyondTarget =
      sortedPromotedExpansionCandidates.filterNot(pid => expansionAdmittedSet.contains(pid) || reserveAdmittedSet.contains(pid))
    val probationRejected = scoreExcluded.filterNot(expansionAdmittedSet.contains)

    val active =
      if (useRecentSignerPool)
        recentSignerPool ++ expansionAdmitted ++ reserveAdmitted
      else
        selected.take(target)
    val exclusions =
      if (useRecentSignerPool)
        demotedRecentSigners.map(Exclusion(_, ExclusionReason.ScoreBelowDemoteThreshold)) ++
          belowRetainRecentSigners.map(Exclusion(_, ExclusionReason.ScoreBelowRetainThreshold)) ++
          excludedRecentOverflow ++
          qualityExcluded.map(Exclusion(_, ExclusionReason.QualityBelowThreshold)) ++
          probationRejected.map(Exclusion(_, ExclusionReason.ScoreBelowPromoteThreshold)) ++
          beyondTarget.map(Exclusion(_, ExclusionReason.BeyondTarget))
      else
        List.empty

    Result(
      active = active,
      exclusions = exclusions,
      recentSignerPoolSize = uncappedRecentSignerPool.size,
      candidateSize = recentSignerPool.size + promotedExpansionCandidates.size + scoreExcluded.size,
      targetSize = target,
      promotedCandidateSize = promotedExpansionCandidates.size,
      scoreExcludedSize = scoreExcluded.size,
      qualityExcludedSize = qualityExcluded.size,
      demotedRecentSignerSize = demotedRecentSigners.size,
      belowRetainRecentSignerSize = belowRetainRecentSigners.size,
      expansionAdmittedSize = expansionAdmitted.size,
      reserveAdmitted = reserveAdmitted,
      reserveAdmittedSize = reserveAdmitted.size,
      probationAdmitted = probationAdmitted,
      probationAdmittedSize = probationAdmitted.size,
      recentSignerMinCount = recentSignerMinCount,
      recentSignerMaxCount = recentSignerMaxCount,
      recentWindowSize = recentSets.size,
      recentFilterApplied = useRecentSignerPool
    )
  }

  def fromCertifiedTimeout(
    selected: List[PeerId],
    recentSigners: SortedMap[SnapshotOrdinal, SortedSet[PeerId]],
    timeoutVoters: Set[PeerId],
    minActiveSize: Int
  ): Result = {
    val recentSets = recentSigners.values.toList.takeRight(TierTransitions.DemotionConsecutiveMisses)
    val recentSignerPool = selected.filter(pid => recentSets.exists(_.contains(pid)))
    val timeoutRetained = selected.filter(timeoutVoters.contains)
    val deterministicFill =
      selected
        .filter(pid => recentSignerPool.contains(pid) && !timeoutRetained.contains(pid))
        .take((minActiveSize - timeoutRetained.size).max(0))
    val retained = timeoutRetained ++ deterministicFill
    val useCertifiedShrink = retained.size >= minActiveSize && retained.size < selected.size
    val active = if (useCertifiedShrink) retained else selected
    val exclusions =
      if (useCertifiedShrink)
        selected.filterNot(active.toSet).map(Exclusion(_, ExclusionReason.CertifiedTimeoutMissing))
      else
        List.empty

    Result(
      active = active,
      exclusions = exclusions,
      recentSignerPoolSize = recentSignerPool.size,
      candidateSize = retained.size,
      targetSize = minActiveSize,
      promotedCandidateSize = 0,
      scoreExcludedSize = 0,
      qualityExcludedSize = 0,
      demotedRecentSignerSize = 0,
      belowRetainRecentSignerSize = 0,
      expansionAdmittedSize = 0,
      reserveAdmitted = List.empty,
      reserveAdmittedSize = 0,
      probationAdmitted = List.empty,
      probationAdmittedSize = 0,
      recentSignerMinCount = recentSignerPool.map(pid => recentSets.count(_.contains(pid))).minOption.getOrElse(0),
      recentSignerMaxCount = recentSignerPool.map(pid => recentSets.count(_.contains(pid))).maxOption.getOrElse(0),
      recentWindowSize = recentSets.size,
      recentFilterApplied = useCertifiedShrink
    )
  }
}
