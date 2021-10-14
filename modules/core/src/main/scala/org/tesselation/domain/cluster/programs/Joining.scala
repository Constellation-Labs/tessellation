package org.tesselation.domain.cluster.programs

import java.security.PublicKey

import cats.Applicative
import cats.effect.Async
import cats.effect.std.Queue
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.traverse._

import org.tesselation.crypto.Signed
import org.tesselation.domain.cluster.services.{Cluster, Session}
import org.tesselation.domain.cluster.storage.{ClusterStorage, SessionStorage}
import org.tesselation.domain.node.NodeStorage
import org.tesselation.effects.GenUUID
import org.tesselation.ext.crypto._
import org.tesselation.http.p2p.P2PClient
import org.tesselation.keytool.security.Signing.verifySignature
import org.tesselation.keytool.security.{SecurityProvider, hex2bytes}
import org.tesselation.kryo.KryoSerializer
import org.tesselation.schema.cluster._
import org.tesselation.schema.node.NodeState
import org.tesselation.schema.peer._

import com.comcast.ip4s.{Host, IpLiteralSyntax}
import fs2.{Pipe, Stream}
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Joining {

  def make[F[_]: Async: GenUUID: SecurityProvider: KryoSerializer](
    nodeStorage: NodeStorage[F],
    clusterStorage: ClusterStorage[F],
    p2pClient: P2PClient[F],
    cluster: Cluster[F],
    session: Session[F],
    sessionStorage: SessionStorage[F],
    selfId: PeerId,
    peerDiscovery: PeerDiscovery[F]
  ): F[Joining[F]] =
    Queue
      .unbounded[F, P2PContext]
      .flatMap(make(_, nodeStorage, clusterStorage, p2pClient, cluster, session, sessionStorage, selfId, peerDiscovery))

  def make[F[_]: Async: GenUUID: SecurityProvider: KryoSerializer](
    joiningQueue: Queue[F, P2PContext],
    nodeStorage: NodeStorage[F],
    clusterStorage: ClusterStorage[F],
    p2pClient: P2PClient[F],
    cluster: Cluster[F],
    session: Session[F],
    sessionStorage: SessionStorage[F],
    selfId: PeerId,
    peerDiscovery: PeerDiscovery[F]
  ): F[Joining[F]] = {
    val joining = new Joining(
      nodeStorage,
      clusterStorage,
      p2pClient,
      cluster,
      session,
      sessionStorage,
      selfId,
      joiningQueue
    ) {}

    def join: Pipe[F, P2PContext, Unit] =
      in =>
        in.evalMap { peer =>
          joining.twoWayHandshake(peer, none) >>
            peerDiscovery
              .discoverFrom(peer)
              .flatMap { _.toList.traverse(joiningQueue.offer(_).void) }
              .void
        }

    val process = Stream.fromQueueUnterminated(joiningQueue).through(join).compile.drain

    Async[F].start(process).as(joining)
  }
}

sealed abstract class Joining[F[_]: Async: GenUUID: SecurityProvider: KryoSerializer] private (
  nodeStorage: NodeStorage[F],
  clusterStorage: ClusterStorage[F],
  p2pClient: P2PClient[F],
  cluster: Cluster[F],
  session: Session[F],
  sessionStorage: SessionStorage[F],
  selfId: PeerId,
  joiningQueue: Queue[F, P2PContext]
) {

  val logger = Slf4jLogger.getLogger[F]

  def join(toPeer: PeerToJoin): F[Unit] =
    for {
      _ <- validateJoinConditions(toPeer)
      _ <- session.createSession
      _ <- nodeStorage.setNodeState(NodeState.SessionStarted)

      _ <- joiningQueue.offer(toPeer)
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

  private def twoWayHandshake(
    withPeer: PeerToJoin,
    remoteAddress: Option[Host],
    skipJoinRequest: Boolean = false
  ): F[Peer] =
    for {
      registrationRequest <- p2pClient.sign.getRegistrationRequest.run(withPeer)

      _ <- validateHandshake(registrationRequest, remoteAddress)

      signRequest <- GenUUID[F].make.map(SignRequest.apply)
      signedSignRequest <- p2pClient.sign.sign(signRequest).run(withPeer)

      publicKey <- withPeer.id.toPublic
      _ <- verifySignRequest(signRequest, signedSignRequest, publicKey)
        .ifM(Applicative[F].unit, HandshakeSignatureNotValid.raiseError[F, Unit])

      peer = Peer(
        registrationRequest.id,
        registrationRequest.ip,
        registrationRequest.publicPort,
        registrationRequest.p2pPort,
        registrationRequest.session
      )

      _ <- clusterStorage.addPeer(peer)

      _ <- if (skipJoinRequest) {
        Applicative[F].unit
      } else {
        cluster.getRegistrationRequest
          .map(JoinRequest.apply)
          .flatMap(p2pClient.sign.joinRequest(_).run(withPeer))
      }

    } yield peer

  private def validateHandshake(registrationRequest: RegistrationRequest, remoteAddress: Option[Host]): F[Unit] =
    for {
      ip <- registrationRequest.ip.pure[F]

      _ <- if (ip.toString != host"127.0.0.1".toString && ip.toString != host"localhost".toString)
        Applicative[F].unit
      else LocalHostNotPermitted.raiseError[F, Unit]

      _ <- remoteAddress.fold(Applicative[F].unit)(
        ra => if (ip.compare(ra) == 0) Applicative[F].unit else InvalidRemoteAddress.raiseError[F, Unit]
      )

      _ <- if (registrationRequest.id != selfId) Applicative[F].unit else IdDuplicationFound.raiseError[F, Unit]
    } yield ()

  private def verifySignRequest[A <: AnyRef](data: A, signed: Signed[SignRequest], publicKey: PublicKey): F[Boolean] =
    Either
      .catchOnly[NumberFormatException](hex2bytes(signed.hashSignature.value))
      .liftTo[F]
      .flatMap { signatureBytes =>
        data.hashF.flatMap { hash =>
          verifySignature(hash.value.getBytes, signatureBytes)(publicKey)
        }
      }

  def joinRequest(joinRequest: JoinRequest, remoteAddress: Host): F[Unit] =
    for {
      _ <- sessionStorage.getToken.flatMap(_.fold(SessionDoesNotExist.raiseError[F, Unit])(_ => Applicative[F].unit))

      registrationRequest = joinRequest.registrationRequest

      withPeer = PeerToJoin(
        registrationRequest.id,
        registrationRequest.ip,
        registrationRequest.p2pPort
      )
      _ <- twoWayHandshake(withPeer, remoteAddress.some, skipJoinRequest = true)
    } yield ()
}
