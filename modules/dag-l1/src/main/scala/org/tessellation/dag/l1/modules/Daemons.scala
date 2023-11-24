package org.tessellation.dag.l1.modules

import cats.Parallel
import cats.effect.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.functor._
import cats.syntax.traverse._

import org.tessellation.dag.l1.infrastructure.healthcheck.HealthCheckDaemon
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import org.tessellation.sdk.domain.Daemon
import org.tessellation.sdk.infrastructure.cluster.daemon.NodeStateDaemon
import org.tessellation.sdk.infrastructure.collateral.daemon.CollateralDaemon
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.security.SecurityProvider

object Daemons {

  def start[
    F[_]: Async: SecurityProvider: KryoSerializer: Random: Parallel: Metrics: Supervisor,
    P <: StateProof,
    S <: Snapshot,
    SI <: SnapshotInfo[P]
  ](
    storages: Storages[F, P, S, SI],
    services: Services[F, P, S, SI],
    healthChecks: HealthChecks[F]
  ): F[Unit] =
    List[Daemon[F]](
      NodeStateDaemon.make(storages.node, services.gossip),
      CollateralDaemon.make(services.collateral, storages.lastSnapshot, storages.cluster),
      HealthCheckDaemon.make(healthChecks)
    ).traverse(_.start).void

}
