package org.tessellation.dag.l1.modules

import cats.effect.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.functor._

import org.tessellation.dag.l1.http.p2p.P2PClient
import org.tessellation.effects.GenUUID
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.config.types.HealthCheckConfig
import org.tessellation.node.shared.domain.cluster.services.Session
import org.tessellation.node.shared.infrastructure.healthcheck.ping.{PingHealthCheckConsensus, PingHealthCheckConsensusDriver}
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}

import org.http4s.client.Client

object HealthChecks {

  def make[
    F[_]: Async: KryoSerializer: GenUUID: Random: Supervisor,
    P <: StateProof,
    S <: Snapshot,
    SI <: SnapshotInfo[P]
  ](
    storages: Storages[F, P, S, SI],
    services: Services[F, P, S, SI],
    programs: Programs[F, P, S, SI],
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
      new HealthChecks(_) {}
    }
  }
}

sealed abstract class HealthChecks[F[_]] private (
  val ping: PingHealthCheckConsensus[F]
)
