package org.tessellation.sdk.infrastructure.cluster.storage

import cats.data.Ior
import cats.effect.{Async, Ref}
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.order._
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

      def addPeer(peer: Peer): F[Boolean] =
        updatePeerAndGet(peer.id) { maybeCurrPeer =>
          maybeCurrPeer.filter { currPeer =>
            currPeer.session >= peer.session
          }.orElse(peer.some)
        }.map {
          case (_, newValue) =>
            newValue.exists(_ === peer)
        }

      def hasPeerId(id: PeerId): F[Boolean] =
        peers(id).get.map(_.isDefined)

      def hasPeerHostPort(host: Host, p2pPort: Port): F[Boolean] =
        getPeers.map(_.exists(peer => peer.ip == host && peer.p2pPort == p2pPort))

      def updatePeerState(id: PeerId, state: NodeState): F[Boolean] =
        updatePeerAndGet(id)(_.map(Peer._State.replace(state)(_))).map {
          case (oldValue, newValue) => oldValue =!= newValue
        }

      def setPeerResponsiveness(id: PeerId, responsiveness: PeerResponsiveness): F[Unit] =
        updatePeerAndGet(id)(_.map(_.focus(_.responsiveness).replace(responsiveness))).void

      def removePeer(id: PeerId): F[Unit] =
        updatePeerAndGet(id)(_ => none).void

      def removePeers(ids: Set[PeerId]): F[Unit] =
        ids.toList.traverse(removePeer).void

      def peerChanges: Stream[F, Ior[Peer, Peer]] =
        topic.subscribe(maxQueuedPeerChanges)

      private def updatePeerAndGet(id: PeerId)(fn: Option[Peer] => Option[Peer]): F[(Option[Peer], Option[Peer])] =
        peers(id).modify(wrapUpdateFn(fn)).flatTap {
          case (oldValue, newValue) =>
            Ior.fromOptions(oldValue, newValue).traverse(topic.publish1).whenA(oldValue =!= newValue)
        }

      private def wrapUpdateFn(fn: Option[Peer] => Option[Peer])(
        oldValue: Option[Peer]
      ): (Option[Peer], (Option[Peer], Option[Peer])) = {
        val newValue = fn(oldValue)
        (newValue, (oldValue, newValue))
      }

      private def generateToken: F[ClusterSessionToken] =
        Generation.make[F].map(ClusterSessionToken(_))
    }

}
