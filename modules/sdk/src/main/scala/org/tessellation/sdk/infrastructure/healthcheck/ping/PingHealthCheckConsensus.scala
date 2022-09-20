package org.tessellation.sdk.infrastructure.healthcheck.ping

import cats.Applicative
import cats.effect._
import cats.effect.std.Random
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.contravariantSemigroupal._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._
import cats.syntax.traverse._
import cats.syntax.traverseFilter._

import scala.concurrent.duration._

import org.tessellation.effects.GenUUID
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.cluster.{PeerToJoin, SessionToken}
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.Peer.toP2PContext
import org.tessellation.schema.peer.{Peer, PeerId}
import org.tessellation.sdk.config.types.HealthCheckConfig
import org.tessellation.sdk.domain.cluster.programs.Joining
import org.tessellation.sdk.domain.cluster.services.Session
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.healthcheck.consensus.types.{ConsensusRounds, HealthCheckRoundId}
import org.tessellation.sdk.domain.healthcheck.consensus.{HealthCheckConsensus, HealthCheckConsensusDriver}
import org.tessellation.sdk.http.p2p.clients.NodeClient
import org.tessellation.sdk.infrastructure.healthcheck.ping.{PeerAvailable, PeerUnavailable}

import com.comcast.ip4s.{Host, Port}
import eu.timepit.refined.auto.autoUnwrap
import eu.timepit.refined.types.numeric.PosInt
import org.http4s.client.Client

class PingHealthCheckConsensus[F[_]: Async: GenUUID: Random](
  clusterStorage: ClusterStorage[F],
  joining: Joining[F],
  selfId: PeerId,
  driver: HealthCheckConsensusDriver[
    PingHealthCheckKey,
    PingHealthCheckStatus,
    PingConsensusHealthStatus,
    PingHealthCheckConsensusDecision
  ],
  config: HealthCheckConfig,
  gossip: Gossip[F],
  nodeClient: NodeClient[F],
  rounds: Ref[F, ConsensusRounds[
    F,
    PingHealthCheckKey,
    PingHealthCheckStatus,
    PingConsensusHealthStatus,
    PingHealthCheckConsensusDecision
  ]],
  waitingProposals: Ref[F, Set[PingConsensusHealthStatus]],
  httpClient: PingHealthCheckHttpClient[F]
) extends HealthCheckConsensus[
      F,
      PingHealthCheckKey,
      PingHealthCheckStatus,
      PingConsensusHealthStatus,
      PingHealthCheckConsensusDecision
    ](
      clusterStorage,
      selfId,
      driver,
      gossip,
      config,
      waitingProposals
    ) {

  def allRounds: Ref[F, ConsensusRounds[
    F,
    PingHealthCheckKey,
    PingHealthCheckStatus,
    PingConsensusHealthStatus,
    PingHealthCheckConsensusDecision
  ]] =
    rounds

  def ownStatus(key: PingHealthCheckKey): F[Fiber[F, Throwable, PingHealthCheckStatus]] =
    Spawn[F].start(checkPeer(key.id, key.ip, key.p2pPort, key.session))

  def statusOnError(key: PingHealthCheckKey): PingHealthCheckStatus = PeerCheckUnexpectedError(key.id)

  def periodic: F[Unit] =
    Applicative[F].whenA(config.ping.enabled) {
      checkRandomPeers
    }

  override def startOwnRound(key: PingHealthCheckKey): F[Unit] =
    Applicative[F].whenA(config.ping.enabled) {
      super.startOwnRound(key)
    }

  override def participateInRound(key: PingHealthCheckKey, roundIds: Set[HealthCheckRoundId]): F[Unit] =
    Applicative[F].whenA(config.ping.enabled) {
      super.participateInRound(key, roundIds)
    }

  override def startRound(key: PingHealthCheckKey, roundIds: Set[HealthCheckRoundId]): F[Unit] =
    Applicative[F].whenA(config.ping.enabled) {
      super.startRound(key, roundIds)
    }

  override def handleProposal(proposal: PingConsensusHealthStatus, depth: Int): F[Unit] =
    Applicative[F].whenA(config.ping.enabled) {
      super.handleProposal(proposal, depth)
    }

  def onOutcome(
    outcomes: ConsensusRounds.Outcome[
      F,
      PingHealthCheckKey,
      PingHealthCheckStatus,
      PingConsensusHealthStatus,
      PingHealthCheckConsensusDecision
    ]
  ): F[Unit] =
    outcomes.toList.traverse {
      case (key, t) =>
        def rejoin =
          joining.rejoin(PeerToJoin(key.id, key.ip, key.p2pPort)).handleErrorWith(err => logger.error(err)(s"Rejoin conditions not met"))
        t match {
          case (DecisionKeepPeer, round) =>
            round.getRoundIds.flatMap { roundIds =>
              round.ownConsensusHealthStatus.map(_.status).flatMap {
                case PeerAvailable(_) | PeerUnavailable(_) | PeerCheckTimeouted(_) | PeerCheckUnexpectedError(_) =>
                  logger.info(s"Outcome for key ${key.show}: available - no action required | Round ids: ${roundIds.show}")
                case _: PeerMismatch =>
                  logger.info(
                    s"Outcome for key ${key.show}: available, own: mismatch - removing and rejoining | Round ids: ${roundIds.show}"
                  ) >>
                    clusterStorage.removePeer(key.id) >>
                    rejoin
                case _: PeerUnknown =>
                  logger.info(s"Outcome for key ${key.show}: available, own: unknown - rejoining | Round ids: ${roundIds.show}") >>
                    rejoin
              }
            }
          case (DecisionPeerMismatch, round) =>
            round.getRoundIds.flatMap { roundIds =>
              round.ownConsensusHealthStatus.map(_.status).flatMap {
                case _: PeerMismatch =>
                  logger.info(s"Outcome for key ${key.show}: mismatch - ignoring healthcheck round | Round ids: ${roundIds.show}")
                case PeerAvailable(_) | PeerUnavailable(_) | PeerUnknown(_) | PeerCheckTimeouted(_) | PeerCheckUnexpectedError(_) =>
                  logger.info(s"Outcome for key ${key.show}: mismatch - removing peer | Round ids: ${roundIds.show}") >>
                    clusterStorage.removePeer(key.id)
              }
            }
          case (DecisionDropPeer, round) =>
            round.getRoundIds.flatMap { roundIds =>
              clusterStorage
                .getPeer(key.id)
                .map(_.exists(_.session === key.session))
                .ifM(
                  logger.info(s"Outcome for key ${key.show}: unavailable - removing peer | Round ids: ${roundIds.show}") >>
                    clusterStorage.removePeer(key.id),
                  logger.info(
                    s"Outcome for key ${key.show}: unavailable - known peer has a different session, ignoring | Round ids: ${roundIds.show}"
                  )
                )
            }
        }
    }.void

  private def checkRandomPeers: F[Unit] =
    clusterStorage.getResponsivePeers
      .map(_.filterNot(NodeState.is(NodeState.leaving ++ NodeState.absent)).toList)
      .flatMap(peers => peersUnderConsensus.map(uc => peers.filterNot(p => uc.contains(p.id))))
      .flatMap(pickRandomPeers(_, config.ping.concurrentChecks))
      .flatMap(_.traverse(p => checkPeer(p.id, p.ip, p.p2pPort, p.session).map(s => p -> s)))
      .map(_.filterNot {
        case (_, status) => status.isInstanceOf[PeerAvailable]
      }.map {
        case (id, _) => id
      })
      .flatMap(_.filterA(p => ensureOffline(p.id, p.ip, p.p2pPort, p.session)))
      .flatMap(_.traverse { peer =>
        startOwnRound(PingHealthCheckKey(peer.id, peer.ip, peer.p2pPort, peer.session))
      })
      .void

  def triggerCheckForPeer(peer: Peer): F[Unit] =
    peersUnderConsensus
      .map(_.contains(peer.id))
      .ifM(
        Applicative[F].unit,
        startOwnRound(PingHealthCheckKey(peer.id, peer.ip, peer.p2pPort, peer.session))
      )

  def requestProposal(
    peer: PeerId,
    roundIds: Set[HealthCheckRoundId],
    ownProposal: PingConsensusHealthStatus
  ): F[Option[PingConsensusHealthStatus]] =
    clusterStorage
      .getPeer(peer)
      .flatMap(_.map(toP2PContext).traverse(httpClient.requestProposal(roundIds, ownProposal).run))
      .map(_.flatten)

  private def checkPeer(
    peer: PeerId,
    ip: Host,
    p2pPort: Port,
    session: SessionToken,
    timeout: FiniteDuration = config.ping.defaultCheckTimeout
  ): F[PingHealthCheckStatus] =
    timeoutTo(
      clusterStorage
        .getPeer(peer)
        .flatMap {
          _.fold(PeerUnknown(peer).pure[F].widen[PingHealthCheckStatus]) { p =>
            if (p.ip === ip && p.port === p2pPort && p.session === session) {
              nodeClient.health
                .run(p)
                .ifM(
                  PeerAvailable(peer).pure[F].widen[PingHealthCheckStatus],
                  PeerUnavailable(peer).pure[F].widen[PingHealthCheckStatus]
                )
            } else {
              PeerMismatch(peer).pure[F].widen[PingHealthCheckStatus]
            }
          }
        }
        .handleError(_ => PeerCheckUnexpectedError(peer)),
      timeout,
      PeerCheckTimeouted(peer).pure[F].widen[PingHealthCheckStatus]
    )

  private def ensureOffline(
    peer: PeerId,
    ip: Host,
    p2pPort: Port,
    session: SessionToken,
    timeout: FiniteDuration = config.ping.defaultCheckTimeout,
    attempts: Int = config.ping.defaultCheckAttempts
  ): F[Boolean] =
    checkPeer(peer, ip, p2pPort, session, timeout).flatMap {
      case PeerAvailable(_)                 => false.pure[F]
      case PeerMismatch(_) | PeerUnknown(_) => true.pure[F]
      case PeerUnavailable(_) | PeerCheckTimeouted(_) | PeerCheckUnexpectedError(_) =>
        if (attempts > 0) {
          Temporal[F].sleep(config.ping.ensureCheckInterval).flatMap { _ =>
            ensureOffline(peer, ip, p2pPort, session, timeout, attempts - 1)
          }
        } else
          true.pure[F]
    }

  private def pickRandomPeers(peers: List[Peer], count: PosInt): F[List[Peer]] =
    Random[F].shuffleList(peers).map(_.take(count))

  private def timeoutTo[A](fa: F[A], after: FiniteDuration, fallback: F[A]): F[A] =
    Spawn[F].race(Temporal[F].sleep(after), fa).flatMap {
      case Left(_)  => fallback
      case Right(a) => a.pure[F]
    }
}

object PingHealthCheckConsensus {

  def make[F[_]: Async: KryoSerializer: GenUUID: Random](
    clusterStorage: ClusterStorage[F],
    joining: Joining[F],
    selfId: PeerId,
    driver: HealthCheckConsensusDriver[
      PingHealthCheckKey,
      PingHealthCheckStatus,
      PingConsensusHealthStatus,
      PingHealthCheckConsensusDecision
    ],
    config: HealthCheckConfig,
    gossip: Gossip[F],
    nodeClient: NodeClient[F],
    client: Client[F],
    session: Session[F]
  ): F[PingHealthCheckConsensus[F]] = {
    def mkRounds =
      Ref
        .of[F, ConsensusRounds[
          F,
          PingHealthCheckKey,
          PingHealthCheckStatus,
          PingConsensusHealthStatus,
          PingHealthCheckConsensusDecision
        ]](
          ConsensusRounds[
            F,
            PingHealthCheckKey,
            PingHealthCheckStatus,
            PingConsensusHealthStatus,
            PingHealthCheckConsensusDecision
          ](List.empty, Map.empty)
        )

    def mkWaitingProposals = Ref.of[F, Set[PingConsensusHealthStatus]](Set.empty)
    def httpClient = PingHealthCheckHttpClient.make[F](client, session)

    (mkRounds, mkWaitingProposals).mapN { (rounds, waitingProposals) =>
      new PingHealthCheckConsensus(
        clusterStorage,
        joining,
        selfId,
        driver,
        config,
        gossip,
        nodeClient,
        rounds,
        waitingProposals,
        httpClient
      )
    }
  }
}
