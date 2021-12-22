package org.tessellation.domain.cluster.programs

import cats.Applicative
import cats.effect.std.Queue
import cats.effect.{Async, Spawn, Temporal}
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.traverse._

import scala.concurrent.duration.DurationInt

import org.tessellation.config.AppEnvironment
import org.tessellation.config.AppEnvironment.Dev
import org.tessellation.effects.GenUUID
import org.tessellation.http.p2p.P2PClient
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.ID.Id
import org.tessellation.schema.cluster._
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer._
import org.tessellation.sdk.domain.cluster.services.{Cluster, Session}
import org.tessellation.sdk.domain.cluster.storage.{ClusterStorage, SessionStorage}
import org.tessellation.sdk.domain.node.NodeStorage
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

import com.comcast.ip4s.{Host, IpLiteralSyntax}
import fs2.{Pipe, Stream}
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Joining {

  def make[F[_]: Async: GenUUID: SecurityProvider: KryoSerializer](
    environment: AppEnvironment,
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
      .flatMap(
        make(
          _,
          environment,
          nodeStorage,
          clusterStorage,
          p2pClient,
          cluster,
          session,
          sessionStorage,
          selfId,
          peerDiscovery
        )
      )

  def make[F[_]: Async: GenUUID: SecurityProvider: KryoSerializer](
    joiningQueue: Queue[F, P2PContext],
    environment: AppEnvironment,
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
      environment,
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
  environment: AppEnvironment,
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

      _ <- Spawn[F].start {
        Temporal[F].sleep(15.seconds) >> nodeStorage.setNodeState(NodeState.Ready)
      } // @mwadon: visualization, just for demo purpose
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

      _ <- verifySignRequest(signRequest, signedSignRequest, PeerId._Id.get(withPeer.id))
        .ifM(Applicative[F].unit, HandshakeSignatureNotValid.raiseError[F, Unit])

      peer = Peer(
        registrationRequest.id,
        registrationRequest.ip,
        registrationRequest.publicPort,
        registrationRequest.p2pPort,
        registrationRequest.session,
        NodeState.Unknown
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

      _ <- if (environment == Dev || ip.toString != host"127.0.0.1".toString && ip.toString != host"localhost".toString)
        Applicative[F].unit
      else LocalHostNotPermitted.raiseError[F, Unit]

      _ <- remoteAddress.fold(Applicative[F].unit)(
        ra => if (ip.compare(ra) == 0) Applicative[F].unit else InvalidRemoteAddress.raiseError[F, Unit]
      )

      _ <- if (registrationRequest.id != selfId) Applicative[F].unit else IdDuplicationFound.raiseError[F, Unit]
    } yield ()

  private def verifySignRequest(signRequest: SignRequest, signed: Signed[SignRequest], id: Id): F[Boolean] =
    for {
      isSignedRequestConsistent <- (signRequest == signed.value).pure[F]
      isSignerCorrect = signed.proofs.forall(_.id == id)
      hasValidSignature <- signed.hasValidSignature
    } yield isSignedRequestConsistent && isSignerCorrect && hasValidSignature

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
