package org.tesselation.infrastructure.cluster

import cats.Applicative
import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tesselation.domain.cluster._
import org.tesselation.http.p2p.P2PClient
import org.tesselation.schema.cluster._
import org.tesselation.schema.peer.Peer

import org.typelevel.log4cats.slf4j.Slf4jLogger

object Cluster {

  def make[F[_]: Async](
    clusterStorage: ClusterStorage[F],
    nodeStorage: NodeStorage[F],
    session: Session[F],
    p2pClient: P2PClient[F]
  ): Cluster[F] =
    new Cluster[F] {

      implicit val logger = Slf4jLogger.getLogger[F]

      /**
        * Join process:
        * 1. Node state allows to join
        * 2. Register peer
        * 3. Fetch data
        * 4. Joining height ???
        *
        * Register peer:
        * 1. Create session token
        * 2. Check whitelisting
        * 3. Get peer registration request, validate and save it locally
        * 3. Send own registration request to the peer
        * 4. Check registration two-way
        */

      def join(toPeer: PeerToJoin): F[Unit] =
        for {
          _ <- validateJoinConditions(toPeer: PeerToJoin)
          sessionToken <- session.createSession

          _ <- twoWayHandshake(toPeer)

          _ <- clusterStorage.addPeer(Peer(toPeer.id, toPeer.ip, toPeer.p2pPort, toPeer.p2pPort))
        } yield ()

      private def validateJoinConditions(toPeer: PeerToJoin): F[Unit] =
        for {
          nodeState <- nodeStorage.getNodeState

          canJoinCluster <- nodeStorage.canJoinCluster
          _ <- if (!canJoinCluster) NodeStateDoesNotAllowForJoining(nodeState).raiseError[F, Unit]
          else Applicative[F].unit

          hasPeerId <- clusterStorage.hasPeerId(toPeer.id)
          _ <- if (hasPeerId) PeerIdInUse(toPeer.id).raiseError[F, Unit] else Applicative[F].unit

          hasPeerHostPort <- clusterStorage.hasPeerHostPort(toPeer.ip, toPeer.p2pPort)
          _ <- if (hasPeerHostPort) PeerHostPortInUse(toPeer.ip, toPeer.p2pPort).raiseError[F, Unit]
          else Applicative[F].unit
        } yield ()

      private def twoWayHandshake(withPeer: PeerToJoin): F[Unit] =
        for {
          _ <- Async[F].unit
          registraionRequest <- p2pClient.sign.getRegistrationRequest.run(withPeer)
//          _ <- validate
//          _ <- handshake
//          _ <- storePeer
        } yield ()
    }

}
