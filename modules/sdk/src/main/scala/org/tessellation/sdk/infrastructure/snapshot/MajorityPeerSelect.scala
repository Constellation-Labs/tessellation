package org.tessellation.sdk.infrastructure.snapshot

import cats.MonadThrow
import cats.data.NonEmptyList
import cats.effect.Async
import cats.effect.std.Random
import cats.effect.syntax.concurrent._
import cats.syntax.applicativeError._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.list._

import scala.util.control.NoStackTrace

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.node.NodeState.Ready
import org.tessellation.schema.peer.Peer
import org.tessellation.schema.peer.Peer.toP2PContext
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.snapshot.PeerSelect
import org.tessellation.sdk.domain.snapshot.PeerSelect._
import org.tessellation.sdk.http.p2p.clients.L0GlobalSnapshotClient
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

  def make[F[_]: Async: KryoSerializer: Random](
    storage: ClusterStorage[F],
    snapshotClient: L0GlobalSnapshotClient[F]
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
        .parTraverseN(maxConcurrentPeerInquiries)(peer => getSnapshotHashByPeer(peer, majorityOrdinal).attempt)
        .flatMap { peerSnapshotHashes =>
          MonadThrow[F].fromOption(
            peerSnapshotHashes.collect { case Right(peerSnapshot) => peerSnapshot }.toNel,
            NoPeersToSelect
          )
        }
        .map(_.groupMap { case (_, hash) => hash } { case (peer, _) => peer })
      peerCandidates = peerDistribution.values.toList.sortBy(-_.length).head
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

    def getSnapshotHashByPeer(peer: Peer, ordinal: SnapshotOrdinal): F[(Peer, Hash)] =
      snapshotClient.get(ordinal).run(peer).flatMap(_.toHashed).map(snapshot => (peer, snapshot.hash))
  }
}
