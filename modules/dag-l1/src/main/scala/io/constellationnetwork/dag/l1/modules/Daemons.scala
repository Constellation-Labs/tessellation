package io.constellationnetwork.dag.l1.modules

import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.functor._
import cats.syntax.traverse._

import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.domain.Daemon
import io.constellationnetwork.node.shared.infrastructure.cluster.daemon.NodeStateDaemon
import io.constellationnetwork.node.shared.infrastructure.collateral.daemon.CollateralDaemon
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}

object Daemons {

  def start[
    F[_]: Async: Supervisor,
    P <: StateProof,
    S <: Snapshot,
    SI <: SnapshotInfo[P],
    R <: CliMethod
  ](
    storages: Storages[F, P, S, SI],
    services: Services[F, P, S, SI, R]
  ): F[Unit] =
    List[Daemon[F]](
      NodeStateDaemon.make(storages.node, services.gossip),
      CollateralDaemon.make(services.collateral, storages.lastSnapshot, storages.cluster)
    ).traverse(_.start).void

}
