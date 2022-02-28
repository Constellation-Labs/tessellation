package org.tessellation.sdk.domain.cluster.programs

import cats.MonadThrow
import cats.effect.Sync
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._

import scala.util.control.NoStackTrace

import org.tessellation.schema.peer.{L0Peer, P2PContext}
import org.tessellation.sdk.domain.cluster.programs.L0PeerDiscovery.L0PeerDiscoveryError
import org.tessellation.sdk.domain.cluster.storage.L0ClusterStorage
import org.tessellation.sdk.http.p2p.clients.L0ClusterClient

import org.typelevel.log4cats.slf4j.Slf4jLogger

object L0PeerDiscovery {

  def make[F[_]: Sync](
    l0ClusterClient: L0ClusterClient[F],
    l0ClusterStorage: L0ClusterStorage[F]
  ): L0PeerDiscovery[F] =
    new L0PeerDiscovery[F](l0ClusterClient, l0ClusterStorage) {}

  case object L0PeerDiscoveryError extends NoStackTrace {
    override def getMessage: String = s"Error during L0 peer discovery!"
  }
}

sealed abstract class L0PeerDiscovery[F[_]: Sync] private (
  l0ClusterClient: L0ClusterClient[F],
  l0ClusterStorage: L0ClusterStorage[F]
) {

  val logger = Slf4jLogger.getLogger[F]

  def discoverFrom(peer: P2PContext): F[Unit] =
    l0ClusterClient.getPeers
      .run(peer)
      .map(_.map(L0Peer.fromPeer))
      .flatMap(l0ClusterStorage.addPeers)
      .handleErrorWith { e =>
        logger.error(e)("Error during L0 peer discovery!") >>
          MonadThrow[F].raiseError[Unit](L0PeerDiscoveryError)
      }
}
