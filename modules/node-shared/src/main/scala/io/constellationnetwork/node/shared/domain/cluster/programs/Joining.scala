package io.constellationnetwork.node.shared.domain.cluster.programs

import cats.data.{EitherT, NonEmptySet}
import cats.effect.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.apply._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.parallel._
import cats.syntax.show._
import cats.{Applicative, MonadThrow, Parallel}

import scala.util.control.NoStackTrace

import io.constellationnetwork.domain.allowance_list.AllowanceListEntry
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.effects.GenUUID
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.env.AppEnvironment.Dev
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.node.shared.domain.Daemon
import io.constellationnetwork.node.shared.domain.cluster.services.{Cluster, Session}
import io.constellationnetwork.node.shared.domain.cluster.storage.{ClusterStorage, SessionStorage}
import io.constellationnetwork.node.shared.domain.healthcheck.LocalHealthcheck
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.http.p2p.clients.SignClient
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.cluster._
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.node.NodeState.{SessionStarted, inCluster}
import io.constellationnetwork.schema.peer.Peer.toP2PContext
import io.constellationnetwork.schema.peer._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import com.comcast.ip4s.{Host, IpLiteralSyntax}
import org.typelevel.log4cats.slf4j.Slf4jLogger

case class FailedToJoin(peer: Peer) extends NoStackTrace {
  override def getMessage: String = s"Joining to peer ${peer.show} failed"
}
case class FailedToDiscoverFrom(peer: Peer, err: Throwable) extends NoStackTrace {
  override def getMessage: String =
    s"Peer discovery from peer ${peer.show} failed because ${err.getMessage}"
}

class Joining[
  F[_]: Async: GenUUID: SecurityProvider: Hasher: Supervisor: Parallel: Random
](
  environment: AppEnvironment,
  nodeStorage: NodeStorage[F],
  clusterStorage: ClusterStorage[F],
  signClient: SignClient[F],
  cluster: Cluster[F],
  session: Session[F],
  sessionStorage: SessionStorage[F],
  localHealthcheck: LocalHealthcheck[F],
  seedlist: Option[Set[SeedlistEntry]],
  selfId: PeerId,
  stateAfterJoining: NodeState,
  versionHash: Hash,
  metagraphVersionHash: Hash,
  peerDiscovery: PeerDiscovery[F],
  allowanceList: Option[Set[AllowanceListEntry]],
  metagraphId: Option[Address],
  consensusConfigHash: Option[Hash] = None
) {

  private val logger = Slf4jLogger.getLogger[F]

  def joinOneOf(peers: NonEmptySet[PeerToJoin]): F[Unit] =
    Random[F].shuffleList(peers.toList).map(_.head).flatMap(join)

  def join(toPeer: PeerToJoin): F[Unit] =
    validateJoinConditions() >>
      session.createSession >>
      Daemon.spawn {
        joinAll(toPeer) >>
          clusterStorage.getResponsivePeers.flatMap { peers =>
            nodeStorage
              .tryModifyState(SessionStarted, stateAfterJoining)
              .whenA(peers.nonEmpty)
          }
      }.start

  def rejoin(withPeer: PeerToJoin): F[Unit] =
    twoWayHandshake(withPeer, None, skipJoinRequest = true).void

  /** Re-announce this node to all known cluster peers after recovery. Unlike `join`, this does NOT require `ReadyToJoin` state — it's
    * called from the recovery download path when the node is in DownloadInProgress/WaitingForObserving. Sends a join request to each known
    * peer so they re-add us to their cluster storage (peers may have removed us via LocalHealthcheck during isolation).
    */
  def rejoinAfterRecovery: F[Unit] =
    for {
      peers <- clusterStorage.getResponsivePeers
      _ <- logger.info(s"[Recovery] Re-announcing to ${peers.size} known peers")
      results <- peers.toList.parTraverse { peer =>
        val peerToJoin = PeerToJoin(peer.id, peer.ip, peer.p2pPort)
        twoWayHandshake(peerToJoin, None, skipJoinRequest = false)
          .map(p => (p.id, true))
          .handleErrorWith { err =>
            logger
              .warn(s"[Recovery] Failed to rejoin with peer ${peer.id.show}: ${err.getMessage}")
              .as((peer.id, false))
          }
      }
      succeeded = results.count(_._2)
      _ <- logger.info(s"[Recovery] Re-joined with $succeeded/${peers.size} peers")
    } yield ()

  private def validateJoinConditions(): F[Unit] =
    for {
      nodeState <- nodeStorage.getNodeState
      canJoinCluster <- nodeStorage.canJoinCluster
      _ <- Applicative[F].unlessA(canJoinCluster)(NodeStateDoesNotAllowForJoining(nodeState).raiseError[F, Unit])
    } yield ()

  def joinRequest(hasCollateral: PeerId => F[Boolean])(joinRequest: JoinRequest, remoteAddress: Host): F[Unit] = {
    for {
      _ <- nodeStorage.getNodeState.map(inCluster).flatMap(NodeNotInCluster.raiseError[F, Unit].unlessA)
      _ <- sessionStorage.getToken.flatMap(_.fold(SessionDoesNotExist.raiseError[F, Unit])(_ => Applicative[F].unit))

      registrationRequest = joinRequest.registrationRequest

      _ <- hasCollateral(registrationRequest.id).flatMap(CollateralNotSatisfied.raiseError[F, Unit].unlessA)

      withPeer = PeerToJoin(
        registrationRequest.id,
        registrationRequest.ip,
        registrationRequest.p2pPort
      )
      _ <- twoWayHandshake(withPeer, remoteAddress.some, skipJoinRequest = true)
    } yield ()
  }.onError(err => logger.error(err)(s"Error during join attempt by ${joinRequest.registrationRequest.id.show}"))

  private def joinTo(toJoin: P2PContext): EitherT[F, Throwable, Peer] =
    twoWayHandshake(toJoin, none).attemptT
      .flatMapF[Throwable, Peer] { p =>
        clusterStorage
          .getPeer(p.id)
          .map(
            Either.fromOption(
              _,
              FailedToJoin(p)
            )
          )
      }

  private def discoverFrom(peer: Peer): F[Set[Peer]] =
    peerDiscovery
      .discoverFrom(peer)
      .handleErrorWith { e =>
        MonadThrow[F].raiseError(FailedToDiscoverFrom(peer, e))
      }

  private def joinAndDiscover(toJoin: P2PContext): EitherT[F, Throwable, Set[P2PContext]] =
    joinTo(toJoin)
      .semiflatMap(discoverFrom)
      .map(_.map(toP2PContext))

  private def joinAll(peerToJoin: P2PContext): F[Unit] = {
    type Agg = (Set[P2PContext], Set[P2PContext])
    type Res = Unit

    (Set(peerToJoin), Set.empty[P2PContext]).tailRecM {
      case (toJoin, _) if toJoin.isEmpty => ().asRight[Agg].pure
      case (toJoin, attempted) =>
        for {
          discovered <- toJoin.toList.parTraverse { peer =>
            joinAndDiscover(peer).valueOrF { err =>
              logger
                .warn(err)(s"Failed to join and discover from ${peer.show}")
                .as(Set.empty[P2PContext])
            }
          }
          reduced = discovered.combineAll
          updatedAttempted = attempted ++ toJoin
          updatedToJoin = reduced.diff(updatedAttempted)
        } yield (updatedToJoin, updatedAttempted).asLeft[Res]
    }
  }

  private def twoWayHandshake(
    withPeer: PeerToJoin,
    remoteAddress: Option[Host],
    skipJoinRequest: Boolean = false
  ): F[Peer] =
    for {
      _ <- validateSeedlist(withPeer)

      registrationRequest <- signClient.getRegistrationRequest.run(withPeer)

      _ <- validateHandshake(registrationRequest, remoteAddress)

      signRequest <- GenUUID[F].make.map(SignRequest.apply)
      signedSignRequest <- signClient.sign(signRequest).run(withPeer)

      _ <- verifySignRequest(signRequest, signedSignRequest, PeerId._Id.get(withPeer.id))
        .ifM(Applicative[F].unit, HandshakeSignatureNotValid.raiseError[F, Unit])

      _ <-
        if (skipJoinRequest) {
          Applicative[F].unit
        } else {
          clusterStorage
            .setToken(registrationRequest.clusterSession)
            .flatMap(_ => cluster.getRegistrationRequest)
            .map(JoinRequest.apply)
            .flatMap(signClient.joinRequest(_).run(withPeer))
            .ifM(
              Applicative[F].unit,
              new Throwable(s"Unexpected error occured when joining with peer=${withPeer.id}.").raiseError[F, Unit]
            )
        }

      peer = Peer(
        registrationRequest.id,
        registrationRequest.ip,
        registrationRequest.publicPort,
        registrationRequest.p2pPort,
        registrationRequest.clusterSession,
        registrationRequest.session,
        registrationRequest.state,
        Responsive,
        registrationRequest.jar
      )

      _ <- clusterStorage
        .addPeer(peer)
        .ifM(
          localHealthcheck.cancel(registrationRequest.id),
          PeerAlreadyJoinedWithDifferentRegistrationData(registrationRequest.id).raiseError[F, Unit]
        )
    } yield peer

  private def validateSeedlist(peer: PeerToJoin): F[Unit] =
    PeerNotOnSeedlist(peer.id)
      .raiseError[F, Unit]
      .unlessA(seedlist.map(_.map(_.peerId)).forall(_.contains(peer.id)))

  private def validateHandshake(registrationRequest: RegistrationRequest, remoteAddress: Option[Host]): F[Unit] =
    for {

      _ <- VersionMismatch.raiseError[F, Unit].whenA(registrationRequest.version =!= versionHash)
      _ <- MetagraphVersionMismatch.raiseError[F, Unit].whenA(registrationRequest.metagraphVersion =!= metagraphVersionHash)
      _ <- EnvMismatch.raiseError[F, Unit].whenA(registrationRequest.environment =!= environment)

      metagraphIdMismatch =
        (registrationRequest.metagraphId, metagraphId)
          .mapN(_ =!= _)
          .getOrElse(false)

      _ <- MetagraphIdMismatch.raiseError[F, Unit].whenA(metagraphIdMismatch)

      ip = registrationRequest.ip
      existingPeer <- clusterStorage.getPeer(registrationRequest.id)

      _ <- existingPeer match {
        case Some(peer) if peer.session <= registrationRequest.session => Applicative[F].unit
        case None                                                      => Applicative[F].unit
        case _ =>
          PeerAlreadyJoinedWithNewerSession(registrationRequest.id, ip, registrationRequest.p2pPort, registrationRequest.session)
            .raiseError[F, Unit]
      }

      ownClusterId = clusterStorage.getClusterId

      _ <- Applicative[F].unlessA(registrationRequest.clusterId == ownClusterId)(ClusterIdDoesNotMatch.raiseError[F, Unit])

      ownClusterSession <- clusterStorage.getToken

      _ <- ownClusterSession match {
        case Some(session) if session === registrationRequest.clusterSession => Applicative[F].unit
        case None                                                            => Applicative[F].unit
        case _                                                               => ClusterSessionDoesNotMatch.raiseError[F, Unit]
      }

      _ <-
        Applicative[F].unlessA(environment == Dev || ip.toString != host"127.0.0.1".toString && ip.toString != host"localhost".toString) {
          LocalHostNotPermitted.raiseError[F, Unit]
        }

      _ <- remoteAddress.fold(Applicative[F].unit)(ra =>
        Applicative[F].unlessA(ip.compare(ra) == 0)(InvalidRemoteAddress.raiseError[F, Unit])
      )

      _ <- Applicative[F].unlessA(registrationRequest.id != selfId)(IdDuplicationFound.raiseError[F, Unit])

      seedlistHash <- seedlist.map(_.map(_.peerId)).hash
      _ <- Applicative[F].unlessA(registrationRequest.seedlist === seedlistHash)(SeedlistDoesNotMatch.raiseError[F, Unit])

      allowanceListHash <- allowanceList.map(_.map(_.peerId)).hash
      _ <- Applicative[F].unlessA(registrationRequest.allowanceList === allowanceListHash)(AllowanceListDoesNotMatch.raiseError[F, Unit])

      // Validate consensus config hash if both sides provide it (backward-compatible: skip during rolling upgrades)
      configHashMismatch = (consensusConfigHash, registrationRequest.consensusConfigHash)
        .mapN(_ =!= _)
        .getOrElse(false)
      _ <- ConsensusConfigMismatch.raiseError[F, Unit].whenA(configHashMismatch)

    } yield ()

  private def verifySignRequest(signRequest: SignRequest, signed: Signed[SignRequest], id: Id): F[Boolean] =
    for {
      isSignedRequestConsistent <- (signRequest == signed.value).pure[F]
      isSignerCorrect = signed.proofs.forall(_.id == id)
      hasValidSignature <- signed.hasValidSignature
    } yield isSignedRequestConsistent && isSignerCorrect && hasValidSignature
}
