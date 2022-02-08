package org.tessellation.sdk.infrastructure.healthcheck.ping

import cats.effect._
import cats.syntax.functor._

import org.tessellation.effects.GenUUID
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.healthcheck.consensus.types.ConsensusRounds
import org.tessellation.sdk.domain.healthcheck.consensus.{HealthCheckConsensus, HealthCheckConsensusDriver}

class PingHealthCheckConsensus[F[_]: Async: GenUUID: Ref.Make](
  clusterStorage: ClusterStorage[F],
  selfId: PeerId,
  driver: HealthCheckConsensusDriver[PingHealthCheckKey, PingHealthCheckStatus, PingConsensusHealthStatus],
  gossip: Gossip[F],
  rounds: Ref[F, ConsensusRounds[F, PingHealthCheckKey, PingHealthCheckStatus, PingConsensusHealthStatus]]
) extends HealthCheckConsensus[F, PingHealthCheckKey, PingHealthCheckStatus, PingConsensusHealthStatus](
      clusterStorage,
      selfId,
      driver,
      gossip
    ) {

  def allRounds: Ref[F, ConsensusRounds[F, PingHealthCheckKey, PingHealthCheckStatus, PingConsensusHealthStatus]] =
    rounds

  def ownStatus(key: PingHealthCheckKey): F[Fiber[F, Throwable, PingHealthCheckStatus]] = ???

  def statusOnError(key: PingHealthCheckKey): PingHealthCheckStatus = PeerUnavailable(key.id)
}

object PingHealthCheckConsensus {

  def make[F[_]: Async: GenUUID: Ref.Make](
    clusterStorage: ClusterStorage[F],
    selfId: PeerId,
    driver: HealthCheckConsensusDriver[PingHealthCheckKey, PingHealthCheckStatus, PingConsensusHealthStatus],
    gossip: Gossip[F]
  ): F[PingHealthCheckConsensus[F]] =
    Ref
      .of[F, ConsensusRounds[F, PingHealthCheckKey, PingHealthCheckStatus, PingConsensusHealthStatus]](
        ConsensusRounds[F, PingHealthCheckKey, PingHealthCheckStatus, PingConsensusHealthStatus](List.empty, Map.empty)
      )
      .map {
        new PingHealthCheckConsensus(clusterStorage, selfId, driver, gossip, _)
      }
}
