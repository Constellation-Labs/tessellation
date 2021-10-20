package org.tesselation.infrastructure.cluster.storage

import com.comcast.ip4s.{Host, Port}
import org.tesselation.domain.cluster.storage.ClusterStorage
import org.tesselation.schema.cluster
import org.tesselation.schema.cluster.TrustInfo
import org.tesselation.schema.peer.{Peer, PeerId}

import cats.Monad
import cats.effect.Ref
import cats.syntax.flatMap._
import cats.syntax.functor._

object ClusterStorage {

  def make[F[_]: Monad: Ref.Make]: F[ClusterStorage[F]] = {
    for {
      peers <- Ref[F].of[Set[Peer]](Set.empty)
      trust <- Ref[F].of[Map[PeerId, TrustInfo]](Map.empty)
    } yield make(peers, trust)
  }

  def make[F[_]: Monad](peers: Ref[F, Set[Peer]], trust: Ref[F, Map[PeerId, TrustInfo]]): ClusterStorage[F] =
    new ClusterStorage[F] {

      def getPeers: F[Set[Peer]] =
        peers.get

      def addPeer(peer: Peer): F[Unit] =
        peers.update(_ + peer)

      def hasPeerId(id: PeerId): F[Boolean] =
        peers.get.map(_.exists(_.id == id))

      def hasPeerHostPort(host: Host, p2pPort: Port): F[Boolean] =
        peers.get.map(_.exists(peer => peer.ip == host && peer.p2pPort == p2pPort))

      def updateTrust(trustUpdates: cluster.InternalTrustUpdateBatch): F[Unit] = {
        trust.update{t =>
          t ++ trustUpdates.updates.map{ trustUpdate =>
            val capped = Math.max(Math.min(trustUpdate.trust, 1), -1)
            val updated = t.getOrElse(trustUpdate.id, TrustInfo()).copy(trustLabel = Some(capped))
            trustUpdate.id -> updated
          }.toMap
        }
      }

      def getTrust(): F[Map[PeerId, TrustInfo]] = trust.get

    }

}
