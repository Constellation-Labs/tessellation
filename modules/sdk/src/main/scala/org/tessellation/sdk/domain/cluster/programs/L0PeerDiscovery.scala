package org.tessellation.sdk.domain.cluster.programs

import cats.data.NonEmptySet
import cats.effect.Sync
import cats.effect.std.Random
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Applicative, MonadThrow}

import scala.collection.immutable.SortedSet
import scala.util.control.NoStackTrace

import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.{L0Peer, P2PContext, PeerId}
import org.tessellation.sdk.domain.cluster.programs.L0PeerDiscovery.L0PeerDiscoveryError
import org.tessellation.sdk.domain.cluster.storage.L0ClusterStorage
import org.tessellation.sdk.http.p2p.clients.L0ClusterClient

import org.typelevel.log4cats.slf4j.Slf4jLogger

object L0PeerDiscovery {

  def make[F[_]: Sync: Random](
    l0ClusterClient: L0ClusterClient[F],
    l0ClusterStorage: L0ClusterStorage[F]
  ): L0PeerDiscovery[F] =
    new L0PeerDiscovery[F](l0ClusterClient, l0ClusterStorage) {}

  case object L0PeerDiscoveryError extends NoStackTrace {
    override def getMessage: String = s"Error during L0 peer discovery!"
  }
}

sealed abstract class L0PeerDiscovery[F[_]: Sync: Random] private (
  l0ClusterClient: L0ClusterClient[F],
  l0ClusterStorage: L0ClusterStorage[F]
) {

  val logger = Slf4jLogger.getLogger[F]

  def discover(lastFacilitators: NonEmptySet[PeerId]): F[Unit] =
    l0ClusterStorage.getPeers
      .map(_.map(_.id).intersect(lastFacilitators))
      .map(_.toList)
      .flatMap(Random[F].shuffleList)
      .map(_.head)
      .flatMap(l0ClusterStorage.getPeer)
      .map(_.map(p => P2PContext(p.ip, p.port, p.id)))
      .flatMap(_.fold(Applicative[F].unit) { p =>
        getPeersFrom(p).flatMap(l0ClusterStorage.setPeers)
      })
      .handleErrorWith { error =>
        logger.warn(error)(s"An error occured during L0 peer discovery")
      }

  private def getPeersFrom(peer: P2PContext): F[NonEmptySet[L0Peer]] =
    l0ClusterClient.getPeers
      .run(peer)
      .map(NodeState.ready)
      .map(_.map(L0Peer.fromPeer))
      .flatMap { s =>
        NonEmptySet
          .fromSet(SortedSet.from(s))
          .fold {
            (new Throwable("Unexpected state - no peers found but at least one should be available")).raiseError[F, NonEmptySet[L0Peer]]
          }(Applicative[F].pure)
      }

  def discoverFrom(peer: P2PContext): F[Unit] =
    getPeersFrom(peer)
      .map(_.toSortedSet)
      .map(_.toSet)
      .flatMap(l0ClusterStorage.addPeers)
      .handleErrorWith { e =>
        logger.error(e)("Error during L0 peer discovery!") >>
          MonadThrow[F].raiseError[Unit](L0PeerDiscoveryError)
      }
}
