package org.tessellation.node.shared.domain.cluster.programs

import cats.effect.{Async, Ref}
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.order._

import org.tessellation.node.shared.domain.cluster.storage.ClusterStorage
import org.tessellation.node.shared.http.p2p.clients.ClusterClient
import org.tessellation.schema.peer.{Peer, PeerId}

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
      peersQueue <- cache.updateAndGet(_ - peer)
      peersQueueIds = peersQueue.map(_.id)

      peers <- clusterClient.getDiscoveryPeers.run(peer)
      knownPeers <- clusterStorage.getResponsivePeers

      unknownPeers = peers.filterNot { p =>
        p.id === nodeId ||
        p.id === peer.id ||
        knownPeers.exists(kp => kp.id === p.id && kp.session >= p.session) ||
        peersQueueIds.contains(p.id)
      }

      _ <- cache.update(_ ++ unknownPeers)
    } yield unknownPeers

}
