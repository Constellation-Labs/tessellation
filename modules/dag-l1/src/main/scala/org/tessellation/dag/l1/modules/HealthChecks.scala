package org.tessellation.dag.l1.modules

import cats.effect.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.functor._

import org.tessellation.dag.l1.http.p2p.P2PClient
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.Block
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo}
import org.tessellation.schema.transaction.Transaction
import org.tessellation.sdk.config.types.HealthCheckConfig
import org.tessellation.sdk.domain.cluster.services.Session
import org.tessellation.sdk.effects.GenUUID
import org.tessellation.sdk.infrastructure.healthcheck.ping.{PingHealthCheckConsensus, PingHealthCheckConsensusDriver}

import org.http4s.client.Client

object HealthChecks {

  def make[
    F[_]: Async: KryoSerializer: GenUUID: Random: Supervisor,
    T <: Transaction,
    B <: Block[T],
    S <: Snapshot[T, B],
    SI <: SnapshotInfo
  ](
    storages: Storages[F, T, B, S, SI],
    services: Services[F, T, B, S, SI],
    programs: Programs[F, T, B, S, SI],
    p2pClient: P2PClient[F, T, B, S, SI],
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
      new HealthChecks(_) {}
    }
  }
}

sealed abstract class HealthChecks[F[_]] private (
  val ping: PingHealthCheckConsensus[F]
)
