package org.tessellation.dag.l1.modules

import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.functor._
import cats.syntax.traverse._

import org.tessellation.dag.l1.config.types.AppConfig
import org.tessellation.dag.l1.infrastructure.healthcheck.HealthCheckDaemon
import org.tessellation.node.shared.domain.Daemon
import org.tessellation.node.shared.domain.snapshot.DoubleSignDetect
import org.tessellation.node.shared.infrastructure.cluster.daemon.NodeStateDaemon
import org.tessellation.node.shared.infrastructure.collateral.daemon.CollateralDaemon
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}

object Daemons {

  def start[
    F[_]: Async: Supervisor,
    P <: StateProof,
    S <: Snapshot,
    SI <: SnapshotInfo[P]
  ](
    storages: Storages[F, P, S, SI],
    services: Services[F, P, S, SI],
    healthChecks: HealthChecks[F],
    cfg: AppConfig
  ): F[Unit] =
    List[Daemon[F]](
      NodeStateDaemon.make(storages.node, services.gossip),
      CollateralDaemon.make(services.collateral, storages.lastSnapshot, storages.cluster),
      HealthCheckDaemon.make(healthChecks),
      DoubleSignDetect.daemon(services.doubleSignDetect, cfg.doubleSignDetectDaemon)
    ).traverse(_.start).void

}
