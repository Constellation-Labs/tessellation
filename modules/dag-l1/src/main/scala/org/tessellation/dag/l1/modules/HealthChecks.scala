package org.tessellation.dag.l1.modules

import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.functor._

import org.tessellation.dag.l1.http.p2p.P2PClient
import org.tessellation.effects.GenUUID
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.config.types.HealthCheckConfig
import org.tessellation.sdk.infrastructure.healthcheck.ping.{PingHealthCheckConsensus, PingHealthCheckConsensusDriver}

object HealthChecks {

  def make[F[_]: Async: GenUUID: Random](
    storages: Storages[F],
    services: Services[F],
    programs: Programs[F],
    p2pClient: P2PClient[F],
    config: HealthCheckConfig,
    selfId: PeerId
  ): F[HealthChecks[F]] = {
    def ping = PingHealthCheckConsensus.make(
      storages.cluster,
      programs.joining,
      selfId,
      new PingHealthCheckConsensusDriver(),
      config,
      services.gossip,
      p2pClient.node
    )

    ping.map {
      new HealthChecks(_) {}
    }
  }
}

sealed abstract class HealthChecks[F[_]] private (
  val ping: PingHealthCheckConsensus[F]
)
