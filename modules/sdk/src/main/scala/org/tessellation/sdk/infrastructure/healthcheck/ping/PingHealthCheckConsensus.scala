package org.tessellation.sdk.infrastructure.healthcheck.ping

import cats.Applicative
import cats.effect._
import cats.effect.std.Random
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import cats.syntax.traverseFilter._

import scala.concurrent.duration._

import org.tessellation.effects.GenUUID
import org.tessellation.schema.cluster.{PeerToJoin, SessionToken}
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.Peer.toP2PContext
import org.tessellation.schema.peer.{Peer, PeerId}
import org.tessellation.sdk.config.types.HealthCheckConfig
import org.tessellation.sdk.domain.cluster.programs.Joining
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.healthcheck.consensus.types.ConsensusRounds
import org.tessellation.sdk.domain.healthcheck.consensus.{HealthCheckConsensus, HealthCheckConsensusDriver}
import org.tessellation.sdk.http.p2p.clients.NodeClient
import org.tessellation.sdk.infrastructure.healthcheck.ping.{PeerAvailable, PeerUnavailable}

import com.comcast.ip4s.{Host, Port}
import eu.timepit.refined.auto.autoUnwrap
import eu.timepit.refined.types.numeric.PosInt

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
  ]]
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
      config
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
    checkRandomPeers

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
        def rejoin = joining.join(PeerToJoin(key.id, key.ip, key.p2pPort))
        t match {
          case (DecisionKeepPeer, round) =>
            round.ownConsensusHealthStatus.map(_.status).flatMap {
              case PeerAvailable(_) | PeerUnavailable(_) | PeerCheckTimeouted(_) | PeerCheckUnexpectedError(_) =>
                logger.info(s"Outcome for peer ${key.id}: available - no action required")
              case _: PeerMismatch =>
                logger.info(s"Outcome for peer ${key.id}: available, own: mismatch - removing and rejoining") >>
                  clusterStorage.removePeer(key.id) >>
                  rejoin
              case _: PeerUnknown =>
                logger.info(s"Outcome for peer ${key.id}: available, own: unknown - rejoining") >>
                  rejoin
            }
          case (DecisionPeerMismatch, round) =>
            round.ownConsensusHealthStatus.map(_.status).flatMap {
              case _: PeerMismatch =>
                logger.info(s"Outcome for peer ${key.id}: mismatch - ignoring healthcheck round")
              case PeerAvailable(_) | PeerUnavailable(_) | PeerUnknown(_) | PeerCheckTimeouted(_) |
                  PeerCheckUnexpectedError(_) =>
                logger.info(s"Outcome for peer ${key.id}: mismatch - removing peer") >>
                  clusterStorage.removePeer(key.id)
            }
          case (DecisionDropPeer, _) =>
            clusterStorage
              .getPeer(key.id)
              .map(_.exists(_.session === key.session))
              .ifM(
                logger.info(s"Outcome for peer ${key.id}: unavailable - removing peer") >>
                  clusterStorage.removePeer(key.id),
                logger.info(s"Outcome for peer ${key.id}: unavailable - known peer has a different session, ignoring")
              )
        }
    }.void

  private def checkRandomPeers: F[Unit] =
    clusterStorage.getPeers.map { _.filterNot(p => NodeState.absent.contains(p.state)).toList }
      .flatMap(peers => peersUnderConsensus.map(uc => peers.filterNot(p => uc.contains(p.id))))
      .flatMap(pickRandomPeers(_, config.ping.concurrentChecks))
      .flatMap(_.traverse(p => checkPeer(p.id, p.ip, p.p2pPort, p.session).map(s => (p -> s))))
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

  private def checkPeer(
    peer: PeerId,
    ip: Host,
    p2pPort: Port,
    session: SessionToken,
    timeout: FiniteDuration = config.ping.defaultCheckTimeout
  ): F[PingHealthCheckStatus] =
    timeoutTo(
      {
        clusterStorage
          .getPeer(peer)
          .flatMap {
            _.fold { PeerUnknown(peer).pure[F].widen[PingHealthCheckStatus] } { p =>
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
          .handleError(_ => PeerCheckUnexpectedError(peer))
      },
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

  def make[F[_]: Async: GenUUID: Random](
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
    nodeClient: NodeClient[F]
  ): F[PingHealthCheckConsensus[F]] =
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
      .map {
        new PingHealthCheckConsensus(clusterStorage, joining, selfId, driver, config, gossip, nodeClient, _)
      }
}
