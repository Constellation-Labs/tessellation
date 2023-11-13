package org.tessellation.currency.l0.modules

import cats.effect.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.functor._

import org.tessellation.currency.l0.http.p2p.P2PClient
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.config.types.HealthCheckConfig
import org.tessellation.sdk.domain.cluster.services.Session
import org.tessellation.sdk.domain.healthcheck.{HealthChecks => HealthChecksTrigger}
import org.tessellation.sdk.effects.GenUUID
import org.tessellation.sdk.infrastructure.healthcheck.ping.{PingHealthCheckConsensus, PingHealthCheckConsensusDriver}

import org.http4s.client.Client

object HealthChecks {

  def make[F[_]: Async: KryoSerializer: GenUUID: Random: Supervisor](
    storages: Storages[F],
    services: Services[F],
    programs: Programs[F],
    p2pClient: P2PClient[F],
    client: Client[F],
    session: Session[F],
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
      p2pClient.node,
      client,
      session
    )

    ping.map {
      new HealthChecks(_) {
        def trigger(): F[Unit] = ping.trigger()
      }
    }
  }
}

sealed abstract class HealthChecks[F[_]] private (
  val ping: PingHealthCheckConsensus[F]
) extends HealthChecksTrigger[F]
