package org.tessellation.sdk.infrastructure.snapshot

import cats.MonadThrow
import cats.data.NonEmptyList
import cats.effect.Async
import cats.effect.std.Random
import cats.effect.syntax.concurrent._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.list._

import scala.util.control.NoStackTrace

import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.node.NodeState.Ready
import org.tessellation.schema.peer.Peer
import org.tessellation.schema.peer.Peer.toP2PContext
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo}
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.snapshot.PeerSelect
import org.tessellation.sdk.domain.snapshot.PeerSelect._
import org.tessellation.sdk.http.p2p.clients.SnapshotClient
import org.tessellation.security.hash.Hash

import derevo.cats.show
import derevo.circe.magnolia.encoder
import derevo.derive
import io.circe.syntax.EncoderOps
import org.typelevel.log4cats.slf4j.Slf4jLogger

object MajorityPeerSelect {

  @derive(encoder, show)
  case class FilteredPeerDetails(
    initialPeers: NonEmptyList[Peer],
    latestOrdinals: NonEmptyList[SnapshotOrdinal],
    ordinalDistribution: List[(SnapshotOrdinal, NonEmptyList[Peer])],
    majorityOrdinal: SnapshotOrdinal,
    hashDistribution: List[(Hash, NonEmptyList[Peer])],
    peerCandidates: NonEmptyList[Peer],
    selectedPeer: Peer
  )

  val maxConcurrentPeerInquiries = 10
  val peerSampleRatio = 0.25
  val minSampleSize = 20

  case object NoPeersToSelect extends NoStackTrace

  def make[F[_]: Async: Random, S <: Snapshot[_, _], SI <: SnapshotInfo[_]](
    storage: ClusterStorage[F],
    snapshotClient: SnapshotClient[F, S, SI]
  ): PeerSelect[F] = new PeerSelect[F] {

    val logger = Slf4jLogger.getLoggerFromName[F](peerSelectLoggerName)

    def select: F[Peer] = getFilteredPeerDetails
      .flatTap(details => logger.debug(details.asJson.noSpaces))
      .map(_.selectedPeer)

    def getFilteredPeerDetails: F[FilteredPeerDetails] = for {
      peers <- storage.getResponsivePeers
        .map(_.filter(_.state === Ready))
        .flatMap(getPeerSublist)
        .flatMap { peerSublist =>
          MonadThrow[F].fromOption(peerSublist.toNel, NoPeersToSelect)
        }
      peerOrdinals <- peers.parTraverseN(maxConcurrentPeerInquiries) { peer =>
        snapshotClient.getLatestOrdinal(peer).map((peer, _))
      }
      latestOrdinals = peerOrdinals.map { case (_, ordinal) => ordinal }
      ordinalDistribution = peerOrdinals.groupMap { case (_, ordinal) => ordinal } { case (peer, _) => peer }
      (majorityOrdinal, _) = latestOrdinals.groupBy(identity).maxBy { case (_, ordinals) => ordinals.size }
      peerDistribution <- peers
        .parTraverseN(maxConcurrentPeerInquiries)(getSnapshotHashByPeer(_, majorityOrdinal))
        .flatMap { maybePeerSnapshotHashes =>
          MonadThrow[F].fromOption(
            maybePeerSnapshotHashes.toList.flatten.toNel,
            NoPeersToSelect
          )
        }
        .map(_.groupMap { case (_, hash) => hash } { case (peer, _) => peer })
      peerCandidates = peerDistribution.values.maxBy(_.length)
      selectedPeer <- Random[F].elementOf(peerCandidates.toList)
    } yield
      FilteredPeerDetails(
        peers,
        latestOrdinals,
        ordinalDistribution.toList,
        majorityOrdinal,
        peerDistribution.toList,
        peerCandidates,
        selectedPeer
      )

    def getPeerSublist(peers: Set[Peer]): F[List[Peer]] = {
      val sampleSize = (peers.size * peerSampleRatio).toInt

      Random[F]
        .shuffleList(peers.toList)
        .map(_.take(Math.max(sampleSize, minSampleSize)))
    }

    def getSnapshotHashByPeer(peer: Peer, ordinal: SnapshotOrdinal): F[Option[(Peer, Hash)]] =
      snapshotClient.getHash(ordinal).run(peer).map(_.map((peer, _)))
  }
}
