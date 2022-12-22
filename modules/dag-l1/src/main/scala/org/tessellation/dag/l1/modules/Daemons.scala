package org.tessellation.dag.l1.modules

import cats.Parallel
import cats.effect.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.functor._
import cats.syntax.traverse._

import org.tessellation.dag.l1.infrastructure.healthcheck.HealthCheckDaemon
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.security.SecurityProvider
import org.tessellation.sdk.domain.Daemon
import org.tessellation.sdk.infrastructure.cluster.daemon.NodeStateDaemon
import org.tessellation.sdk.infrastructure.collateral.daemon.CollateralDaemon
import org.tessellation.sdk.infrastructure.metrics.Metrics

object Daemons {

  def start[F[_]: Async: SecurityProvider: KryoSerializer: Random: Parallel: Metrics: Supervisor](
    storages: Storages[F],
    services: Services[F],
    healthChecks: HealthChecks[F]
  ): F[Unit] =
    List[Daemon[F]](
      NodeStateDaemon.make(storages.node, services.gossip),
      CollateralDaemon.make(services.collateral, storages.lastGlobalSnapshotStorage, storages.cluster),
      HealthCheckDaemon.make(healthChecks)
    ).traverse(_.start).void

}
