package org.tessellation.sdk.infrastructure.healthcheck.ping

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
import org.tessellation.schema.cluster.PeerToJoin
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
    Spawn[F].start(checkPeer(key.id, key.ip, key.p2pPort))

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
              case _: PeerAvailable => logger.info(s"Outcome for peer ${key.id}: available - no action required")
              case own              => logger.info(s"Outcome for peer ${key.id}: available, own: ${own} - rejoining") >> rejoin
            }
          case (DecisionPeerMismatch, round) =>
            round.ownConsensusHealthStatus.map(_.status).flatMap {
              case _: PeerMismatch =>
                logger.info(s"Outcome for peer ${key.id}: mismatch - no action required")
              case own =>
                logger.info(s"Outcome for peer ${key.id}: mismatch, own: ${own} - removing peer") >>
                  clusterStorage.removePeer(key.id)
            }
          case (DecisionDropPeer, _) =>
            logger.info(s"Outcome for peer ${key.id}: unavailable - removing peer") >>
              clusterStorage.removePeer(key.id)
        }
    }.void

  private def checkRandomPeers: F[Unit] =
    clusterStorage.getPeers.map { _.filterNot(p => NodeState.absent.contains(p.state)).toList }
      .flatMap(peers => peersUnderConsensus.map(uc => peers.filterNot(p => uc.contains(p.id))))
      .flatMap(pickRandomPeers(_, config.ping.concurrentChecks))
      .flatMap(_.traverse(p => checkPeer(p.id, p.ip, p.p2pPort).map(s => (p -> s))))
      .map(_.filterNot {
        case (_, status) => status.isInstanceOf[PeerAvailable]
      }.map {
        case (id, _) => id
      })
      .flatMap(_.filterA(p => ensureOffline(p.id, p.ip, p.p2pPort)))
      .flatMap(_.traverse { peer =>
        startOwnRound(PingHealthCheckKey(peer.id, peer.ip, peer.p2pPort))
      })
      .void

  private def checkPeer(
    peer: PeerId,
    ip: Host,
    p2pPort: Port,
    timeout: FiniteDuration = config.ping.defaultCheckTimeout
  ): F[PingHealthCheckStatus] =
    timeoutTo(
      {
        clusterStorage
          .getPeer(peer)
          .flatMap {
            _.map(toP2PContext).fold { PeerUnknown(peer).pure[F].widen[PingHealthCheckStatus] } { p =>
              if (p.ip === ip && p.port === p2pPort) {
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
    timeout: FiniteDuration = config.ping.defaultCheckTimeout,
    attempts: Int = config.ping.defaultCheckAttempts
  ): F[Boolean] =
    checkPeer(peer, ip, p2pPort, timeout).flatMap {
      case PeerAvailable(_) => false.pure[F]
      case _ =>
        if (attempts > 0) {
          Temporal[F].sleep(config.ping.ensureCheckInterval).flatMap { _ =>
            ensureOffline(peer, ip, p2pPort, timeout, attempts - 1)
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
