package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId

object ActiveFacilitatorAdmission {

  sealed abstract class ExclusionReason(val label: String)
  object ExclusionReason {
    case object NotRecentSigner extends ExclusionReason("not_recent_signer")
  }

  final case class Exclusion(peerId: PeerId, reason: ExclusionReason)

  final case class Result(
    active: List[PeerId],
    exclusions: List[Exclusion],
    recentSignerPoolSize: Int,
    recentWindowSize: Int,
    recentFilterApplied: Boolean
  )

  def fromRecentSigners(
    selected: List[PeerId],
    recentSigners: SortedMap[SnapshotOrdinal, SortedSet[PeerId]],
    minActiveSize: Int
  ): Result = {
    val recentSets = recentSigners.values.toList.takeRight(TierTransitions.DemotionConsecutiveMisses)
    val recentWindowDeepEnough = recentSets.sizeIs >= TierTransitions.DemotionConsecutiveMisses
    val recentSignerPool =
      if (recentWindowDeepEnough)
        selected.filter(pid => recentSets.exists(_.contains(pid)))
      else
        selected
    val useRecentSignerPool = recentWindowDeepEnough && recentSignerPool.size >= minActiveSize
    val active = if (useRecentSignerPool) recentSignerPool else selected
    val exclusions =
      if (useRecentSignerPool)
        selected.filterNot(recentSignerPool.toSet).map(Exclusion(_, ExclusionReason.NotRecentSigner))
      else
        List.empty

    Result(
      active = active,
      exclusions = exclusions,
      recentSignerPoolSize = recentSignerPool.size,
      recentWindowSize = recentSets.size,
      recentFilterApplied = useRecentSignerPool
    )
  }
}
