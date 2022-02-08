package org.tessellation.modules

import cats.effect.{Async, Ref}
import cats.syntax.functor._

import org.tessellation.effects.GenUUID
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.healthcheck.services.HealthCheck
import org.tessellation.sdk.infrastructure.healthcheck.ping.{PingHealthCheckConsensus, PingHealthCheckConsensusDriver}

object HealthChecks {

  def make[F[_]: Async: GenUUID: Ref.Make](
    storages: Storages[F],
    services: Services[F],
    selfId: PeerId
  ): F[HealthChecks[F]] = {
    def ping = PingHealthCheckConsensus.make(
      storages.cluster,
      selfId,
      new PingHealthCheckConsensusDriver(),
      services.gossip
    )

    ping.map {
      new HealthChecks(_) {}
    }
  }
}

sealed abstract class HealthChecks[F[_]] private (
  val ping: HealthCheck[F]
)
