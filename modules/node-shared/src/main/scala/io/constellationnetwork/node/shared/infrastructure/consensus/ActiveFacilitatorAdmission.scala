package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId

object ActiveFacilitatorAdmission {

  sealed abstract class ExclusionReason(val label: String)
  object ExclusionReason {
    case object NotRecentSigner extends ExclusionReason("not_recent_signer")
    case object QualityBelowThreshold extends ExclusionReason("quality_below_threshold")
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
    expansionAdmittedSize: Int,
    recentSignerMinCount: Int,
    recentSignerMaxCount: Int,
    recentWindowSize: Int,
    recentFilterApplied: Boolean
  )

  def fromRecentSigners(
    selected: List[PeerId],
    recentSigners: SortedMap[SnapshotOrdinal, SortedSet[PeerId]],
    peerQuality: Map[PeerId, (Int, Int)],
    minActiveSize: Int,
    targetActiveSize: Int,
    maxActiveSize: Int,
    minParticipationObservations: Int,
    minParticipationRatio: Double
  ): Result = {
    def qualityRank(pid: PeerId): (Int, Int, Int, String) =
      peerQuality.get(pid) match {
        case Some((completed, participated)) if participated > 0 =>
          val ratio = completed.toDouble / participated.toDouble
          val ratioScaled = (ratio * 1000000).toInt
          val qualityClass =
            if (participated >= minParticipationObservations && ratio < minParticipationRatio) 2
            else if (participated >= minParticipationObservations) 0
            else 1

          (qualityClass, -ratioScaled, -completed, pid.value.value)
        case _ =>
          (1, 0, 0, pid.value.value)
      }

    def isQualityExcluded(pid: PeerId): Boolean =
      peerQuality.get(pid).exists {
        case (completed, participated) if participated >= minParticipationObservations && participated > 0 =>
          completed.toDouble / participated.toDouble < minParticipationRatio
        case _ => false
      }

    val recentSets = recentSigners.values.toList.takeRight(TierTransitions.DemotionConsecutiveMisses)
    def recentSignerCount(pid: PeerId): Int = recentSets.count(_.contains(pid))
    def recentSignerRank(pid: PeerId): (Int, Int, Int, Int, String) = {
      val (qualityClass, ratioRank, completedRank, peerIdRank) = qualityRank(pid)
      (-recentSignerCount(pid), qualityClass, ratioRank, completedRank, peerIdRank)
    }

    val recentWindowDeepEnough = recentSets.sizeIs >= TierTransitions.DemotionConsecutiveMisses
    val uncappedRecentSignerPool =
      if (recentWindowDeepEnough)
        selected.filter(pid => recentSets.exists(_.contains(pid))).sortBy(recentSignerRank)
      else
        selected
    val configuredMax = math.max(minActiveSize, maxActiveSize)
    val target =
      math.min(
        selected.size,
        math.min(configuredMax, List(minActiveSize, targetActiveSize, uncappedRecentSignerPool.size).max)
      )
    val recentSignerPool = uncappedRecentSignerPool.take(target)
    val recentSignerSet = recentSignerPool.toSet
    val admittedRecentSignerCounts = recentSignerPool.map(recentSignerCount)
    val recentSignerMinCount = admittedRecentSignerCounts.minOption.getOrElse(0)
    val recentSignerMaxCount = admittedRecentSignerCounts.maxOption.getOrElse(0)
    val excludedRecentOverflow =
      if (uncappedRecentSignerPool.size > recentSignerPool.size)
        uncappedRecentSignerPool.drop(recentSignerPool.size).map(Exclusion(_, ExclusionReason.BeyondTarget))
      else
        List.empty

    val expansionBase =
      if (recentWindowDeepEnough)
        selected.filterNot(recentSignerSet.contains)
      else
        List.empty
    val (qualityExcluded, expansionCandidates) = expansionBase.partition(isQualityExcluded)
    val sortedExpansionCandidates = expansionCandidates.sortBy(qualityRank)
    val expansionSlots = math.max(0, target - recentSignerPool.size)
    val expansionAdmitted = sortedExpansionCandidates.take(expansionSlots)
    val expansionAdmittedSet = expansionAdmitted.toSet
    val beyondTarget = sortedExpansionCandidates.filterNot(expansionAdmittedSet.contains)

    val useRecentSignerPool = recentWindowDeepEnough && uncappedRecentSignerPool.size >= minActiveSize
    val active =
      if (useRecentSignerPool)
        recentSignerPool ++ expansionAdmitted
      else
        selected.take(target)
    val exclusions =
      if (useRecentSignerPool)
        excludedRecentOverflow ++
          qualityExcluded.map(Exclusion(_, ExclusionReason.QualityBelowThreshold)) ++
          beyondTarget.map(Exclusion(_, ExclusionReason.BeyondTarget))
      else
        List.empty

    Result(
      active = active,
      exclusions = exclusions,
      recentSignerPoolSize = uncappedRecentSignerPool.size,
      candidateSize = recentSignerPool.size + expansionCandidates.size,
      targetSize = target,
      expansionAdmittedSize = expansionAdmitted.size,
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
      expansionAdmittedSize = 0,
      recentSignerMinCount = recentSignerPool.map(pid => recentSets.count(_.contains(pid))).minOption.getOrElse(0),
      recentSignerMaxCount = recentSignerPool.map(pid => recentSets.count(_.contains(pid))).maxOption.getOrElse(0),
      recentWindowSize = recentSets.size,
      recentFilterApplied = useCertifiedShrink
    )
  }
}
