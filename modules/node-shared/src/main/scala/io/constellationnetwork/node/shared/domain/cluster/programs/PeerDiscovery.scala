package io.constellationnetwork.node.shared.domain.cluster.programs

import cats.effect.{Async, Ref, Resource}
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.order._

import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.http.p2p.clients.ClusterClient
import io.constellationnetwork.schema.peer.{Peer, PeerId}

object PeerDiscovery {

  /** Exclusive ownership of the candidates one `discoverFrom` call claimed. The claim lives exactly as long as the `Resource` that produced
    * it: its release removes these candidates (and nothing claimed by another holder) from the discovery queue, whether the join attempts
    * succeeded, failed, or were cancelled. `token` identifies the holder in the queue.
    */
  final case class Claim(token: Long, peers: Set[Peer])

  private[programs] final case class Reservation(owner: Long, peer: Peer)

  def make[F[_]: Async: Ref.Make](
    clusterClient: ClusterClient[F],
    clusterStorage: ClusterStorage[F],
    nodeId: PeerId
  ): F[PeerDiscovery[F]] =
    (Ref.of[F, Map[PeerId, Reservation]](Map.empty), Ref.of[F, Long](0L))
      .mapN(new PeerDiscovery[F](_, _, clusterClient, clusterStorage, nodeId) {})

  trait PeerDiscoveryEnqueue[F[_]] {
    def enqueuePeer(peer: Peer): F[Unit]
  }
}

sealed abstract class PeerDiscovery[F[_]: Async] private (
  queue: Ref[F, Map[PeerId, PeerDiscovery.Reservation]],
  tokens: Ref[F, Long],
  clusterClient: ClusterClient[F],
  clusterStorage: ClusterStorage[F],
  nodeId: PeerId
) {
  import PeerDiscovery._

  /** Every queued candidate, whoever holds its claim. */
  def getPeers: F[Set[Peer]] = queue.get.map(_.values.map(_.peer).toSet)

  /** Query `peer` for its discovery peers and claim, in one atomic step, every reported candidate that is not this node, not `peer`, not a
    * known responsive peer with a session at least as new, and not already claimed by any holder. The request is cancelable (a time budget
    * around the call still interrupts it); the claim step and the registration of its release are one uncancelable step, so a claim is
    * never committed without a holder and is released only by that holder. Release drops exactly this claim's candidates.
    */
  def discoverFrom(peer: Peer): Resource[F, Claim] =
    Resource.makeFull[F, Claim](poll => poll(fetchCandidates(peer)).flatMap(claim))(c => release(c.token))

  private def fetchCandidates(peer: Peer): F[Set[Peer]] =
    (clusterClient.getDiscoveryPeers.run(peer), clusterStorage.getResponsivePeers).mapN { (peers, knownPeers) =>
      peers.filterNot { p =>
        p.id === nodeId ||
        p.id === peer.id ||
        knownPeers.exists(kp => kp.id === p.id && kp.session >= p.session)
      }
    }

  private def claim(candidates: Set[Peer]): F[Claim] =
    tokens.updateAndGet(_ + 1L).flatMap { token =>
      queue.modify { queued =>
        val claimed = candidates.filterNot(p => queued.contains(p.id))
        (queued ++ claimed.iterator.map(p => p.id -> Reservation(token, p)), Claim(token, claimed))
      }
    }

  private def release(token: Long): F[Unit] =
    queue.update(_.filter { case (_, reservation) => reservation.owner =!= token })

}
