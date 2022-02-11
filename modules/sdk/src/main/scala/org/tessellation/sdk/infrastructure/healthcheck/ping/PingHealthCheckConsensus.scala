package org.tessellation.sdk.infrastructure.healthcheck.ping

import cats.effect._
import cats.effect.std.Random
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import cats.syntax.traverseFilter._

import scala.concurrent.duration._

import org.tessellation.effects.GenUUID
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.Peer.toP2PContext
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.config.types.HealthCheckConfig
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.healthcheck.consensus.types.ConsensusRounds
import org.tessellation.sdk.domain.healthcheck.consensus.{HealthCheckConsensus, HealthCheckConsensusDriver}
import org.tessellation.sdk.http.p2p.clients.NodeClient
import org.tessellation.sdk.infrastructure.healthcheck.ping.{PeerAvailable, PeerUnavailable}

import eu.timepit.refined.auto.autoUnwrap
import eu.timepit.refined.types.numeric.PosInt

class PingHealthCheckConsensus[F[_]: Async: GenUUID: Random](
  clusterStorage: ClusterStorage[F],
  selfId: PeerId,
  driver: HealthCheckConsensusDriver[PingHealthCheckKey, PingHealthCheckStatus, PingConsensusHealthStatus],
  config: HealthCheckConfig,
  gossip: Gossip[F],
  nodeClient: NodeClient[F],
  rounds: Ref[F, ConsensusRounds[F, PingHealthCheckKey, PingHealthCheckStatus, PingConsensusHealthStatus]]
) extends HealthCheckConsensus[F, PingHealthCheckKey, PingHealthCheckStatus, PingConsensusHealthStatus](
      clusterStorage,
      selfId,
      driver,
      gossip,
      config
    ) {

  def allRounds: Ref[F, ConsensusRounds[F, PingHealthCheckKey, PingHealthCheckStatus, PingConsensusHealthStatus]] =
    rounds

  def ownStatus(key: PingHealthCheckKey): F[Fiber[F, Throwable, PingHealthCheckStatus]] =
    Spawn[F].start(checkPeer(key.id))

  def statusOnError(key: PingHealthCheckKey): PingHealthCheckStatus = PeerUnavailable(key.id)

  def periodic: F[Unit] =
    checkRandomPeers

  private def checkRandomPeers: F[Unit] =
    clusterStorage.getPeers.map { _.filterNot(p => NodeState.absent.contains(p.state)).map(_.id).toList }
      .flatMap(p => peersUnderConsensus.map(_.toList).map(p.diff))
      .flatMap(pickRandomPeers(_, config.ping.concurrentChecks))
      .flatMap(_.traverse(p => checkPeer(p).map(s => (p -> s))))
      .map(_.filterNot {
        case (_, status) => status.isInstanceOf[PeerAvailable]
      }.map {
        case (id, _) => id
      })
      .flatMap(_.filterA(ensureOffline(_)))
      .flatMap(_.traverse { peer =>
        startOwnRound(PingHealthCheckKey(peer))
      })
      .void

  private def checkPeer(
    peer: PeerId,
    timeout: FiniteDuration = config.ping.defaultCheckTimeout
  ): F[PingHealthCheckStatus] =
    timeoutTo(
      {
        clusterStorage
          .getPeer(peer)
          .flatMap {
            _.map(toP2PContext).fold { PeerUnavailable(peer).pure[F].widen[PingHealthCheckStatus] } { p =>
              nodeClient.health
                .run(p)
                .ifM(
                  PeerAvailable(peer).pure[F].widen[PingHealthCheckStatus],
                  PeerUnavailable(peer).pure[F].widen[PingHealthCheckStatus]
                )
            }
          }
          .handleError(_ => PeerUnavailable(peer))
      },
      timeout,
      PeerUnavailable(peer).pure[F].widen[PingHealthCheckStatus]
    )

  private def ensureOffline(
    peer: PeerId,
    timeout: FiniteDuration = config.ping.defaultCheckTimeout,
    attempts: Int = config.ping.defaultCheckAttempts
  ): F[Boolean] =
    checkPeer(peer, timeout).flatMap {
      case PeerAvailable(_) => false.pure[F]
      case _ =>
        if (attempts > 0) {
          Temporal[F].sleep(config.ping.ensureCheckInterval).flatMap { _ =>
            ensureOffline(peer, timeout, attempts - 1)
          }
        } else
          true.pure[F]
    }

  private def pickRandomPeers(peers: List[PeerId], count: PosInt): F[List[PeerId]] =
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
    selfId: PeerId,
    driver: HealthCheckConsensusDriver[PingHealthCheckKey, PingHealthCheckStatus, PingConsensusHealthStatus],
    config: HealthCheckConfig,
    gossip: Gossip[F],
    nodeClient: NodeClient[F]
  ): F[PingHealthCheckConsensus[F]] =
    Ref
      .of[F, ConsensusRounds[F, PingHealthCheckKey, PingHealthCheckStatus, PingConsensusHealthStatus]](
        ConsensusRounds[F, PingHealthCheckKey, PingHealthCheckStatus, PingConsensusHealthStatus](List.empty, Map.empty)
      )
      .map {
        new PingHealthCheckConsensus(clusterStorage, selfId, driver, config, gossip, nodeClient, _)
      }
}
