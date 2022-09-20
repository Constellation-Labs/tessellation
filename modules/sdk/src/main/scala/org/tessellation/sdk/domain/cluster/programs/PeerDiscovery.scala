package org.tessellation.sdk.domain.cluster.programs

import cats.effect.{Async, Ref}
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.order._
import cats.syntax.traverse._

import org.tessellation.schema.peer.{Peer, PeerId}
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.http.p2p.clients.ClusterClient

object PeerDiscovery {

  def make[F[_]: Async: Ref.Make](
    clusterClient: ClusterClient[F],
    clusterStorage: ClusterStorage[F],
    nodeId: PeerId
  ): F[PeerDiscovery[F]] =
    Ref
      .of[F, Set[Peer]](Set.empty)
      .map(make(_, clusterClient, clusterStorage, nodeId))

  def make[F[_]: Async](
    cache: Ref[F, Set[Peer]],
    clusterClient: ClusterClient[F],
    clusterStorage: ClusterStorage[F],
    nodeId: PeerId
  ): PeerDiscovery[F] = new PeerDiscovery[F](cache, clusterClient, clusterStorage, nodeId) {}

  trait PeerDiscoveryEnqueue[F[_]] {
    def enqueuePeer(peer: Peer): F[Unit]
  }
}

sealed abstract class PeerDiscovery[F[_]: Async] private (
  cache: Ref[F, Set[Peer]],
  clusterClient: ClusterClient[F],
  clusterStorage: ClusterStorage[F],
  nodeId: PeerId
) {

  def getPeers: F[Set[Peer]] = cache.get

  def discoverFrom(peer: Peer): F[Set[Peer]] =
    for {
      _ <- removePeer(peer)

      peers <- clusterClient.getDiscoveryPeers.run(peer)
      knownPeers <- clusterStorage.getResponsivePeers
      peersQueue <- getPeers

      unknownPeers = peers.filterNot { p =>
        p.id === nodeId || p.id === peer.id || knownPeers.filter(kp => kp.id === p.id && kp.session >= p.session).nonEmpty || peersQueue
          .map(_.id)
          .contains(p.id)
      }
      _ <- unknownPeers.toList
        .traverse(addNextPeer)
    } yield unknownPeers

  private def addNextPeer(peer: Peer): F[Unit] =
    cache.modify { c =>
      (c + peer, ())
    }

  private def removePeer(peer: Peer): F[Unit] =
    cache.modify { c =>
      (c - peer, ())
    }

}
