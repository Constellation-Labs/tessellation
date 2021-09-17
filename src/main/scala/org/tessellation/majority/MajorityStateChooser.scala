package org.tessellation.majority

import cats.syntax.all._
import org.tessellation.majority.MajorityStateChooser.ExtendedSnapshotProposal
import org.tessellation.majority.SnapshotStorage.{MajorityHeight, PeersCache, PeersProposals, SnapshotProposalsAtHeight, SnapshotsAtHeight}

class MajorityStateChooser(id: String) {

  def chooseMajorityState(
    createdSnapshots: SnapshotProposalsAtHeight,
    peersProposals: PeersProposals,
    peersCache: PeersCache
  ): SnapshotsAtHeight = {
    val proposals = peersProposals + (id -> createdSnapshots)
    val filteredProposals = filterProposals(proposals, peersCache)
    val mergedProposals = mergeByHeights(filteredProposals, peersCache)

    val flat = mergedProposals.view
      .mapValues(getTheMostQuantity(_, peersCache))
      .mapValues(_.map(_.hash))
      .toMap

    flat.mapFilter(identity)
  }

  private def getTheMostQuantity(
    proposals: Set[ExtendedSnapshotProposal],
    peersCache: PeersCache
  ): Option[ExtendedSnapshotProposal] =
    // TODO: fix ordering warning picked up by the compiler
    proposals.toList.sortBy(o => (-o.trust, -o.percentage, o.hash)).headOption.flatMap { s =>
      if (areAllProposals(proposals, peersCache)) s.some
      else None
    }

  private def areAllProposals(proposals: Set[ExtendedSnapshotProposal], peersCache: PeersCache): Boolean =
    proposals.toList.map(_.n).sum == getProposersCount(proposals.headOption.map(_.height).getOrElse(-1L), peersCache)

  private def getProposersCount(height: Long, peersCache: PeersCache): Int =
    peersCache.count {
      case (_, majorityHeight) => {
        MajorityHeight.isHeightBetween(height)(majorityHeight)
      }
    }

  private def mergeByHeights(
    proposals: PeersProposals,
    peersCache: PeersCache
  ): Map[Long, Set[ExtendedSnapshotProposal]] =
    proposals.map {
      case (id, v) =>
        v.map {
          case (height, p) =>
            (
              height,
              ExtendedSnapshotProposal(
                p.hash,
                height,
                totalReputation(proposals, height, id),
                Set(id),
                1,
                getProposersCount(height, peersCache)
              )
            )
        }
    }.toList
      .map(_.view.mapValues(List(_)).toMap)
      .foldRight(Map.empty[Long, List[ExtendedSnapshotProposal]])(_ |+| _)
      .view
      .mapValues { heightProposals =>
        heightProposals
          .groupBy(_.hash)
          .map {
            case (hash, hashProposals) => {
              val height = hashProposals.headOption.map(_.height).getOrElse(-1L)
              ExtendedSnapshotProposal(
                hash,
                height,
                roundError(hashProposals.foldRight(0.0)(_.trust + _)),
                hashProposals.foldRight(Set.empty[String])(_.ids ++ _),
                heightProposals.size,
                getProposersCount(height, peersCache)
              )
            }
          }
          .toSet
      }
      .toMap

  private def roundError(value: Double): Double =
    if (value < 1.0e-10 && value > -1.0e-10) 0d
    else value

  private def totalReputation(proposals: PeersProposals, height: Long, id: String): Double =
    proposals.values.flatMap {
      _.get(height).map(_.reputation.getOrElse(id, 0d))
    }.sum

  private def filterProposals(
    peersProposals: PeersProposals,
    peersCache: PeersCache
  ): PeersProposals =
    peersProposals.filter {
      case (id, _) =>
        peersCache.contains(id)
    }.map {
      case (id, proposals) =>
        (id, proposals.filter {
          case (height, _) =>
            peersCache.get(id).exists(MajorityHeight.isHeightBetween(height))
        })
    }

}

object MajorityStateChooser {
  def apply(id: String): MajorityStateChooser = new MajorityStateChooser(id)

  case class MajorityIntegrityError(gaps: Set[Long]) extends Throwable {
    def message: String = s"Majority state has gaps: ${gaps.toSeq.sorted.mkString(",")}"
  }

  case class ExtendedSnapshotProposal(
    hash: String,
    height: Long,
    trust: Double,
    ids: Set[String],
    of: Int,
    proposersCount: Int
  ) {
    val n: Int = ids.size

    val percentage: Double = n / of.toDouble
    val totalPercentage: Double = n / proposersCount.toDouble
  }
}
