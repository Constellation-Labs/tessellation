package io.constellationnetwork.dag.l1.modules

import cats.effect.Async
import cats.effect.kernel.Ref
import cats.effect.std.Supervisor
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.domain.Daemon
import io.constellationnetwork.node.shared.infrastructure.cluster.daemon.NodeStateDaemon
import io.constellationnetwork.node.shared.infrastructure.collateral.daemon.CollateralDaemon
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}

object Daemons {

  def start[
    F[_]: Async: Supervisor: Metrics,
    P <: StateProof,
    S <: Snapshot,
    SI <: SnapshotInfo[P],
    R <: CliMethod
  ](
    storages: Storages[F, P, S, SI],
    services: Services[F, P, S, SI, R],
    // SharedServices-owned state-entry timestamp Ref. NodeStateDaemon refreshes it on each
    // transition; Cluster.leave()'s dwell-time guard reads it.
    stateEntryAtRef: Ref[F, FiniteDuration]
  ): F[Unit] =
    List[Daemon[F]](
      NodeStateDaemon.make(storages.node, services.gossip, stateEntryAtRef = Some(stateEntryAtRef)),
      CollateralDaemon.make(services.collateral, storages.lastSnapshot, storages.cluster)
    ).traverse(_.start).void

}
