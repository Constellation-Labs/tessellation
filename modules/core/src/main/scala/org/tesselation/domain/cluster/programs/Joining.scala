package org.tesselation.domain.cluster.programs

import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Applicative, MonadThrow}

import org.tesselation.domain.cluster.services.{Cluster, Session}
import org.tesselation.domain.cluster.storage.{ClusterStorage, NodeStorage}
import org.tesselation.http.p2p.P2PClient
import org.tesselation.schema.cluster._
import org.tesselation.schema.peer
import org.tesselation.schema.peer.{JoinRequest, Peer}

final case class Joining[F[_]: MonadThrow](
  nodeStorage: NodeStorage[F],
  clusterStorage: ClusterStorage[F],
  p2pClient: P2PClient[F],
  cluster: Cluster[F],
  session: Session[F]
) {

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
    } yield ()

  def joinRequest(joinRequest: peer.JoinRequest): F[Unit] =
    for {
      _ <- Applicative[F].unit
      // validate
      // finish handshake
      registrationRequest = joinRequest.registrationRequest
      _ <- clusterStorage.addPeer(
        Peer(
          registrationRequest.id,
          registrationRequest.ip,
          registrationRequest.publicPort,
          registrationRequest.p2pPort
        )
      )
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
      registrationRequest <- p2pClient.sign.getRegistrationRequest.run(withPeer)

      _ <- validateHandshake()

      //          _ <- handshake

      // store session token!
      _ <- clusterStorage.addPeer(
        Peer(
          registrationRequest.id,
          registrationRequest.ip,
          registrationRequest.publicPort,
          registrationRequest.p2pPort
        )
      )

      ownRegistrationRequest <- cluster.getRegistrationRequest
      joinRequest = JoinRequest(registrationRequest = ownRegistrationRequest)
      _ <- p2pClient.sign.joinRequest(joinRequest).run(withPeer)
    } yield ()

  private def validateHandshake(): F[Unit] =
    for {
      _ <- Applicative[F].unit
      // prevent localhost
      // validate external host
      // check status
      // is correct ip and port
      // is self
    } yield ()
}
