package org.tessellation.sdk.domain.cluster.programs

import cats.effect.{Async, Ref}
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

import org.tessellation.schema.peer.{P2PContext, Peer, PeerId}
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.http.p2p.clients.ClusterClient

object PeerDiscovery {

  def make[F[_]: Async: Ref.Make](
    clusterClient: ClusterClient[F],
    clusterStorage: ClusterStorage[F],
    nodeId: PeerId
  ): F[PeerDiscovery[F]] =
    Ref
      .of[F, Set[P2PContext]](Set.empty)
      .map(make(_, clusterClient, clusterStorage, nodeId))

  def make[F[_]: Async](
    cache: Ref[F, Set[P2PContext]],
    clusterClient: ClusterClient[F],
    clusterStorage: ClusterStorage[F],
    nodeId: PeerId
  ): PeerDiscovery[F] = new PeerDiscovery[F](cache, clusterClient, clusterStorage, nodeId) {}

  trait PeerDiscoveryEnqueue[F[_]] {
    def enqueuePeer(peer: Peer): F[Unit]
  }
}

sealed abstract class PeerDiscovery[F[_]: Async] private (
  cache: Ref[F, Set[P2PContext]],
  clusterClient: ClusterClient[F],
  clusterStorage: ClusterStorage[F],
  nodeId: PeerId
) {

  def getPeers: F[Set[P2PContext]] = cache.get

  def discoverFrom(peer: P2PContext): F[Set[P2PContext]] =
    for {
      _ <- removePeer(peer)
      peers <- clusterClient.getDiscoveryPeers.run(peer)
      knownPeers <- clusterStorage.getPeers
      peersQueue <- getPeers
      unknownPeers = peers.filterNot { p =>
        p.id == nodeId || p.id == peer.id || knownPeers.map(_.id).contains(p.id) || peersQueue.map(_.id).contains(p.id)
      }
      _ <- unknownPeers.toList
        .traverse(addNextPeer)
    } yield unknownPeers

  private def addNextPeer(peer: P2PContext): F[Unit] =
    cache.modify { c =>
      (c + peer, ())
    }

  private def removePeer(peer: P2PContext): F[Unit] =
    cache.modify { c =>
      (c - peer, ())
    }

}
