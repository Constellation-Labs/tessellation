package org.tessellation.domain.cluster.programs

import cats.effect.{Async, Ref}
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

import org.tessellation.domain.cluster.storage.ClusterStorage
import org.tessellation.http.p2p.P2PClient
import org.tessellation.schema.peer.{P2PContext, Peer, PeerId}

object PeerDiscovery {

  def make[F[_]: Async: Ref.Make](
    p2pClient: P2PClient[F],
    clusterStorage: ClusterStorage[F],
    nodeId: PeerId
  ): F[PeerDiscovery[F]] =
    Ref
      .of[F, Set[P2PContext]](Set.empty)
      .map(make(_, p2pClient, clusterStorage, nodeId))

  def make[F[_]: Async](
    cache: Ref[F, Set[P2PContext]],
    p2pClient: P2PClient[F],
    clusterStorage: ClusterStorage[F],
    nodeId: PeerId
  ): PeerDiscovery[F] = new PeerDiscovery[F](cache, p2pClient, clusterStorage, nodeId) {}

  trait PeerDiscoveryEnqueue[F[_]] {
    def enqueuePeer(peer: Peer): F[Unit]
  }
}

sealed abstract class PeerDiscovery[F[_]: Async] private (
  cache: Ref[F, Set[P2PContext]],
  p2pClient: P2PClient[F],
  clusterStorage: ClusterStorage[F],
  nodeId: PeerId
) {

  def getPeers: F[Set[P2PContext]] = cache.get

  def discoverFrom(peer: P2PContext): F[Set[P2PContext]] =
    for {
      _ <- removePeer(peer)
      peers <- p2pClient.cluster.getDiscoveryPeers.run(peer)
      knownPeers <- clusterStorage.getPeers
      unknownPeers = peers.filterNot { p =>
        p.id == nodeId || knownPeers.map(_.id).contains(p.id)
      }
      _ <- unknownPeers.toList
        .traverse(addNextPeer)

      peersQueue <- getPeers
    } yield peersQueue

  private def addNextPeer(peer: P2PContext): F[Unit] =
    cache.modify { c =>
      (c + peer, ())
    }

  private def removePeer(peer: P2PContext): F[Unit] =
    cache.modify { c =>
      (c - peer, ())
    }

}
