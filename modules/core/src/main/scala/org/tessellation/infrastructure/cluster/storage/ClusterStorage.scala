package org.tessellation.infrastructure.cluster.storage

import cats.Monad
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.traverse._

import org.tessellation.domain.cluster.storage.ClusterStorage
import org.tessellation.schema.node
import org.tessellation.schema.peer.{Peer, PeerId}

import com.comcast.ip4s.{Host, Port}
import io.chrisdavenport.mapref.MapRef

object ClusterStorage {

  def make[F[_]: Async]: F[ClusterStorage[F]] =
    MapRef
      .ofSingleImmutableMap[F, PeerId, Peer](Map.empty)
      .map(make(_))

  def make[F[_]: Monad](peers: MapRef[F, PeerId, Option[Peer]]): ClusterStorage[F] =
    new ClusterStorage[F] {

      def getPeers: F[Set[Peer]] =
        peers.keys.flatMap(_.map(peers(_).get).sequence).map(_.flatten.toSet)

      def getPeer(id: PeerId): F[Option[Peer]] =
        peers(id).get

      def addPeer(peer: Peer): F[Unit] =
        peers(peer.id).set(peer.some)

      def hasPeerId(id: PeerId): F[Boolean] =
        peers(id).get.map(_.isDefined)

      def hasPeerHostPort(host: Host, p2pPort: Port): F[Boolean] =
        getPeers.map(_.exists(peer => peer.ip == host && peer.p2pPort == p2pPort))

      def setPeerState(id: PeerId, state: node.NodeState): F[Unit] =
        peers(id).update {
          case Some(peer) => Peer._State.replace(state)(peer).some
          case None       => none
        }

    }

}
