package org.tessellation.currency.modules

import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.functor._
import cats.syntax.traverse._

import org.tessellation.currency.infrastructure.snapshot.CurrencySnapshotEventsPublisherDaemon
import org.tessellation.sdk.domain.Daemon
import org.tessellation.sdk.domain.healthcheck.HealthChecks
import org.tessellation.sdk.infrastructure.cluster.daemon.NodeStateDaemon
import org.tessellation.sdk.infrastructure.collateral.daemon.CollateralDaemon
import org.tessellation.sdk.infrastructure.healthcheck.daemon.HealthCheckDaemon
import org.tessellation.sdk.infrastructure.snapshot.daemon.DownloadDaemon

object Daemons {

  def start[F[_]: Async: Supervisor](
    storages: Storages[F],
    services: Services[F],
    programs: Programs[F],
    queues: Queues[F],
    healthChecks: HealthChecks[F]
  ): F[Unit] =
    List[Daemon[F]](
      NodeStateDaemon.make(storages.node, services.gossip),
      DownloadDaemon.make(storages.node, programs.download),
      HealthCheckDaemon.make(healthChecks),
      CurrencySnapshotEventsPublisherDaemon.make(queues.l1Output, services.gossip),
      CollateralDaemon.make(services.collateral, storages.snapshot, storages.cluster)
    ).traverse(_.start).void

}
