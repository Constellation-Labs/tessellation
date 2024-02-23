package org.tessellation.dag.l0.modules

import cats.effect.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.all._

import org.tessellation.dag.l0.http.p2p.P2PClient
import org.tessellation.effects.GenUUID
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.config.types.HealthCheckConfig
import org.tessellation.node.shared.domain.cluster.services.Session
import org.tessellation.node.shared.domain.healthcheck.{HealthChecks => HealthChecksTrigger}
import org.tessellation.node.shared.infrastructure.healthcheck.ping.{PingHealthCheckConsensus, PingHealthCheckConsensusDriver}
import org.tessellation.schema.peer.PeerId

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
        def trigger(): F[Unit] = this.ping.trigger()
      }
    }
  }
}

sealed abstract class HealthChecks[F[_]] private (
  val ping: PingHealthCheckConsensus[F]
) extends HealthChecksTrigger[F]
