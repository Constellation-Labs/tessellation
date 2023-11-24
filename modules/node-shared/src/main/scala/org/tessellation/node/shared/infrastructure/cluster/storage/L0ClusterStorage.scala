package org.tessellation.node.shared.infrastructure.cluster.storage

import cats.data.{NonEmptyMap, NonEmptySet}
import cats.effect.std.Random
import cats.effect.{Ref, Sync}
import cats.syntax.contravariant._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Monad, Order}

import org.tessellation.ext.cats.data.NonEmptyMapOps
import org.tessellation.node.shared.domain.cluster.storage.L0ClusterStorage
import org.tessellation.schema.peer.{L0Peer, PeerId}

object L0ClusterStorage {

  implicit val order: Order[L0Peer] = Order[PeerId].contramap(_.id)
  implicit val ordering: Ordering[L0Peer] = order.toOrdering

  def make[F[_]: Sync: Random](l0Peer: L0Peer): F[L0ClusterStorage[F]] =
    Ref
      .of[F, NonEmptyMap[PeerId, L0Peer]](NonEmptyMap.one(l0Peer.id, l0Peer))
      .map(make(_))

  def make[F[_]: Monad: Random](peers: Ref[F, NonEmptyMap[PeerId, L0Peer]]): L0ClusterStorage[F] =
    new L0ClusterStorage[F] {

      def getPeers: F[NonEmptySet[L0Peer]] =
        peers.get.map(_.values)

      def getPeer(id: PeerId): F[Option[L0Peer]] =
        peers.get.map(_.lookup(id))

      def getRandomPeer: F[L0Peer] =
        getPeers
          .map(_.toNonEmptyList.toList)
          .flatMap(Random[F].shuffleList)
          .map(_.head)

      def addPeers(l0Peers: Set[L0Peer]): F[Unit] =
        peers.modify { current =>
          val updated = l0Peers.map(p => p.id -> p).toMap.foldLeft(current)(_.add(_))

          (updated, ())
        }

      def setPeers(l0Peers: NonEmptySet[L0Peer]): F[Unit] = {
        val head = l0Peers.head
        peers.set {
          NonEmptyMap.of((head.id, head), l0Peers.tail.map(p => p.id -> p).toSeq: _*)
        }
      }
    }
}
