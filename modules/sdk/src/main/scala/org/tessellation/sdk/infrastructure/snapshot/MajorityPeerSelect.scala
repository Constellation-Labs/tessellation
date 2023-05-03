package org.tessellation.sdk.infrastructure.snapshot

import cats.MonadThrow
import cats.data.NonEmptyList
import cats.effect.Async
import cats.effect.std.Random
import cats.effect.syntax.concurrent._
import cats.syntax.applicative._
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
import org.tessellation.sdk.http.p2p.clients.L0GlobalSnapshotClient
import org.tessellation.security.hash.Hash

import org.typelevel.log4cats.slf4j.Slf4jLogger

object MajorityPeerSelect {

  val maxConcurrentPeerInquiries = 10
  val peerSampleRatio = 0.25
  val minSampleSize = 20

  case object NoPeersToSelect extends NoStackTrace

  def make[F[_]: Async: KryoSerializer: Random](
    storage: ClusterStorage[F],
    snapshotClient: L0GlobalSnapshotClient[F]
  ): PeerSelect[F] = new PeerSelect[F] {

    val logger = Slf4jLogger.getLoggerFromClass[F](MajorityPeerSelect.getClass)

    def select: F[Peer] = selectPeer

    def selectPeer: F[Peer] = storage.getResponsivePeers
      .map(_.filter(_.state === Ready))
      .flatMap(getPeerSublist)
      .flatMap { peers =>
        peers.toNel match {
          case Some(value) => value.pure[F]
          case None =>
            logger.error("No Ready peers were found to be selected.") >>
              NoPeersToSelect.raiseError[F, NonEmptyList[Peer]]
        }
      }
      .flatMap(filterPeerList)
      .map(_.toList)
      .flatMap(Random[F].elementOf)

    def filterPeerList(peers: NonEmptyList[Peer]): F[NonEmptyList[Peer]] =
      peers
        .parTraverseN(maxConcurrentPeerInquiries)(snapshotClient.getLatestOrdinal(_))
        .map(_.groupBy(identity).maxBy { case (_, ordinals) => ordinals.size })
        .map { case (majorityOrdinal, _) => majorityOrdinal }
        .flatMap { majorityOrdinal =>
          peers
            .parTraverseN(maxConcurrentPeerInquiries)(peer => getSnapshotHashByPeer(peer, majorityOrdinal).attempt)
        }
        .flatMap { peerSnapshotHashes =>
          MonadThrow[F].fromOption(
            peerSnapshotHashes.collect { case Right(peerSnapshot) => peerSnapshot }.toNel,
            NoPeersToSelect
          )
        }
        .map(_.groupMap { case (_, hash) => hash } { case (peer, _) => peer })
        .map(_.values.toList.sortBy(-_.size))
        .map(_.head)

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
