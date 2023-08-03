package org.tessellation.modules

import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.functor._
import cats.syntax.traverse._

import org.tessellation.config.types.AppConfig
import org.tessellation.infrastructure.snapshot.GlobalSnapshotEventsPublisherDaemon
import org.tessellation.infrastructure.trust.TrustStorageUpdater
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.Daemon
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
    healthChecks: HealthChecks[F],
    nodeId: PeerId,
    cfg: AppConfig
  ): F[Unit] =
    List[Daemon[F]](
      NodeStateDaemon.make(storages.node, services.gossip),
      DownloadDaemon.make(storages.node, programs.download),
      Daemon.periodic(storages.trust.updateTrustWithBiases(nodeId), cfg.trust.daemon.interval),
      HealthCheckDaemon.make(healthChecks),
      GlobalSnapshotEventsPublisherDaemon.make(queues.stateChannelOutput, queues.l1Output, services.gossip),
      CollateralDaemon.make(services.collateral, storages.globalSnapshot, storages.cluster),
      TrustStorageUpdater.daemon(services.trustStorageUpdater)
    ).traverse(_.start).void

}
