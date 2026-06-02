package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId

object LeaderEligibility {

  sealed abstract class ExclusionReason(val label: String)
  object ExclusionReason {
    case object NotGraduated extends ExclusionReason("not_graduated")
    case object NotRecentSigner extends ExclusionReason("not_recent_signer")
  }

  final case class Exclusion(peerId: PeerId, reason: ExclusionReason)

  final case class Result(
    leaderPool: List[PeerId],
    exclusions: List[Exclusion],
    graduatedPoolSize: Int,
    recentSignerPoolSize: Int,
    recentWindowSize: Int,
    recentFilterApplied: Boolean
  )

  def fromRecentSigners(
    core: List[PeerId],
    peerQuality: Map[PeerId, (Int, Int)],
    recentSigners: SortedMap[SnapshotOrdinal, SortedSet[PeerId]],
    minParticipationObservations: Int,
    minLeaderPoolSize: Int
  ): Result = {
    val graduated = core.filter { pid =>
      val (completed, participated) = peerQuality.getOrElse(pid, (0, 0))
      participated >= minParticipationObservations && completed >= 1
    }
    val useGraduated = graduated.size >= minLeaderPoolSize
    val graduationBase = if (useGraduated) graduated else core
    val graduationExclusions =
      if (useGraduated)
        core.filterNot(graduated.toSet).map(Exclusion(_, ExclusionReason.NotGraduated))
      else
        List.empty

    val recentSets = recentSigners.values.toList.takeRight(TierTransitions.DemotionConsecutiveMisses)
    val recentWindowDeepEnough = recentSets.sizeIs >= TierTransitions.DemotionConsecutiveMisses
    val recentSignerPool =
      if (recentWindowDeepEnough)
        graduationBase.filter(pid => recentSets.forall(_.contains(pid)))
      else
        graduationBase
    val useRecentSignerPool = recentWindowDeepEnough && recentSignerPool.size >= minLeaderPoolSize
    val leaderPool = if (useRecentSignerPool) recentSignerPool else graduationBase
    val recentExclusions =
      if (useRecentSignerPool)
        graduationBase.filterNot(recentSignerPool.toSet).map(Exclusion(_, ExclusionReason.NotRecentSigner))
      else
        List.empty

    Result(
      leaderPool = leaderPool,
      exclusions = graduationExclusions ++ recentExclusions,
      graduatedPoolSize = graduated.size,
      recentSignerPoolSize = recentSignerPool.size,
      recentWindowSize = recentSets.size,
      recentFilterApplied = useRecentSignerPool
    )
  }
}
