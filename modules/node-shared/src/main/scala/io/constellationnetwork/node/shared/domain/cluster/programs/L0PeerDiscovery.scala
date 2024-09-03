package io.constellationnetwork.node.shared.domain.cluster.programs

import cats.data.NonEmptySet
import cats.effect.Sync
import cats.effect.std.Random
import cats.syntax.all._
import cats.{Applicative, MonadThrow}

import scala.collection.immutable.SortedSet
import scala.util.control.NoStackTrace

import io.constellationnetwork.node.shared.domain.cluster.programs.L0PeerDiscovery.L0PeerDiscoveryError
import io.constellationnetwork.node.shared.domain.cluster.storage.L0ClusterStorage
import io.constellationnetwork.node.shared.http.p2p.clients.L0ClusterClient
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.{L0Peer, P2PContext, PeerId}
import io.constellationnetwork.syntax.sortedCollection.sortedSetSyntax

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
      .flatMap(_.fold(Applicative[F].unit) { p =>
        getPeersFrom(p)
          .map(_.filter(peer => lastFacilitators.contains(peer.id)).toSortedSet.toNes)
          .flatMap(_.traverse_(l0ClusterStorage.setPeers))
      })
      .handleErrorWith { error =>
        logger.warn(error)(s"An error occured during L0 peer discovery")
      }

  private def getPeersFrom(peer: P2PContext): F[NonEmptySet[L0Peer]] =
    l0ClusterClient.getPeers
      .run(peer)
      .map(_.filter(p => NodeState.ready.contains(p.state)))
      .map(_.map(L0Peer.fromPeerInfo))
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
      .flatMap(l0ClusterStorage.addPeers)
      .handleErrorWith { e =>
        logger.error(e)("Error during L0 peer discovery!") >>
          MonadThrow[F].raiseError[Unit](L0PeerDiscoveryError)
      }
}
