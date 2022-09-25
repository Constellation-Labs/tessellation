package org.tessellation.sdk.infrastructure.cluster.storage

import cats.data.Ior
import cats.effect.{Async, Ref}
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.traverse._

import org.tessellation.schema.cluster.{ClusterId, ClusterSessionAlreadyExists, ClusterSessionToken}
import org.tessellation.schema.generation.Generation
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer._
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage

import com.comcast.ip4s.{Host, Port}
import fs2.Stream
import fs2.concurrent.Topic
import io.chrisdavenport.mapref.MapRef
import monocle.syntax.all._

object ClusterStorage {

  private val maxQueuedPeerChanges = 1000

  def make[F[_]: Async](clusterId: ClusterId, initialPeers: Map[PeerId, Peer] = Map.empty): F[ClusterStorage[F]] =
    for {
      topic <- Topic[F, Ior[Peer, Peer]]
      peers <- MapRef.ofSingleImmutableMap[F, PeerId, Peer](initialPeers)
      session <- Ref.of[F, Option[ClusterSessionToken]](None)
    } yield make(clusterId, topic, peers, session)

  def make[F[_]: Async](
    clusterId: ClusterId,
    topic: Topic[F, Ior[Peer, Peer]],
    peers: MapRef[F, PeerId, Option[Peer]],
    session: Ref[F, Option[ClusterSessionToken]]
  ): ClusterStorage[F] =
    new ClusterStorage[F] {

      def createToken: F[ClusterSessionToken] =
        generateToken.flatMap { generatedToken =>
          session.modify {
            case None        => (generatedToken.some, generatedToken.pure[F])
            case Some(token) => (token.some, ClusterSessionAlreadyExists.raiseError[F, ClusterSessionToken])
          }.flatMap(identity)
        }

      def getToken: F[Option[ClusterSessionToken]] =
        session.get

      def setToken(token: ClusterSessionToken): F[Unit] =
        session.set(token.some)

      def getClusterId: ClusterId = clusterId

      def getPeers: F[Set[Peer]] =
        peers.keys.flatMap(_.map(peers(_).get).sequence).map(_.flatten.toSet)

      def getResponsivePeers: F[Set[Peer]] =
        getPeers.map(_.filter(_.responsiveness === Responsive))

      def getPeer(id: PeerId): F[Option[Peer]] =
        peers(id).get

      def addPeer(peer: Peer): F[Unit] =
        setPeer(peer.id)(peer.some)

      def hasPeerId(id: PeerId): F[Boolean] =
        peers(id).get.map(_.isDefined)

      def hasPeerHostPort(host: Host, p2pPort: Port): F[Boolean] =
        getPeers.map(_.exists(peer => peer.ip == host && peer.p2pPort == p2pPort))

      def setPeerState(id: PeerId, state: NodeState): F[Unit] =
        updatePeer(id)(_.map(Peer._State.replace(state)(_)))

      def setPeerResponsiveness(id: PeerId, responsiveness: PeerResponsiveness): F[Unit] =
        updatePeer(id)(_.map(_.focus(_.responsiveness).replace(responsiveness)))

      def removePeer(id: PeerId): F[Unit] =
        setPeer(id)(none)

      def removePeers(ids: Set[PeerId]): F[Unit] =
        ids.toList.traverse(removePeer).void

      def peerChanges: Stream[F, Ior[Peer, Peer]] =
        topic.subscribe(maxQueuedPeerChanges)

      private def setPeer(id: PeerId)(value: Option[Peer]): F[Unit] = updatePeer(id)(_ => value)

      private def updatePeer(id: PeerId)(fn: Option[Peer] => Option[Peer]): F[Unit] =
        peers(id).modify(wrapUpdateFn(id)(fn)).flatTap(_.traverse(topic.publish1)).void

      private def wrapUpdateFn(peerId: PeerId)(fn: Option[Peer] => Option[Peer])(
        oldValue: Option[Peer]
      ): (Option[Peer], Option[Ior[Peer, Peer]]) = {
        val newValue = fn(oldValue)
        if (newValue === oldValue)
          (oldValue, none)
        else
          (newValue, Ior.fromOptions(oldValue, newValue))
      }

      private def generateToken: F[ClusterSessionToken] =
        Generation.make[F].map(ClusterSessionToken(_))
    }

}
