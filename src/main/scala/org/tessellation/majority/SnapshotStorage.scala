package org.tessellation.majority

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.concurrent.Ref
import io.chrisdavenport.mapref.MapRef
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import org.tessellation.majority.SnapshotStorage.{MajorityHeight, SnapshotProposalsAtHeight, SnapshotsAtHeight}

import scala.collection.SortedMap

case class SnapshotStorage() {

  val lastMajorityState: Ref[IO, SnapshotsAtHeight] = Ref.unsafe(Map.empty)
  val activeBetweenHeights: Ref[IO, Option[MajorityHeight]] = Ref.unsafe[IO, Option[MajorityHeight]](None)
  val createdSnapshots: Ref[IO, SnapshotProposalsAtHeight] = Ref.unsafe(Map.empty)
  val peerProposals: MapRef[IO, String, Option[SnapshotProposalsAtHeight]] =
    MapRefUtils.ofConcurrentHashMap[IO, String, SnapshotProposalsAtHeight]()

}

object SnapshotStorage {
  case class MajorityHeight(joined: Option[Long], left: Option[Long] = None) {
    def isFinite = joined.isDefined && left.isDefined
  }

  object MajorityHeight {
    def isHeightBetween(height: Long)(majorityHeights: NonEmptyList[MajorityHeight]): Boolean =
      majorityHeights.exists(isHeightBetween(height, _))

    def isHeightBetween(height: Long, majorityHeight: MajorityHeight): Boolean =
      majorityHeight.joined.exists(_ < height) && majorityHeight.left.forall(_ >= height)
  }

  case class SnapshotProposal(hash: String, height: Long, reputation: SortedMap[String, Double])

  object SnapshotProposal {
    implicit val codec: Codec[SnapshotProposal] = deriveCodec
  }

  type SnapshotsAtHeight = Map[Long, String] // height -> hash
  type SnapshotProposalsAtHeight = Map[Long, SnapshotProposal]
  type PeersProposals = Map[String, SnapshotProposalsAtHeight]
  type PeersCache = Map[String, NonEmptyList[MajorityHeight]]
}
