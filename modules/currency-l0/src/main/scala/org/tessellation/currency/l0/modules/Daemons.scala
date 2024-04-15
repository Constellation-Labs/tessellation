package org.tessellation.currency.l0.modules

import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.functor._
import cats.syntax.traverse._

import org.tessellation.currency.dataApplication.BaseDataApplicationL0Service
import org.tessellation.currency.l0.config.types.AppConfig
import org.tessellation.currency.l0.snapshot.CurrencySnapshotEventsPublisherDaemon
import org.tessellation.node.shared.domain.Daemon
import org.tessellation.node.shared.infrastructure.cluster.daemon.NodeStateDaemon
import org.tessellation.node.shared.infrastructure.collateral.daemon.CollateralDaemon
import org.tessellation.node.shared.infrastructure.snapshot.daemon.{DownloadDaemon, SelectablePeerDiscoveryDelay}
import org.tessellation.security.HasherSelector

object Daemons {

  def start[F[_]: Async: Supervisor: HasherSelector](
    storages: Storages[F],
    services: Services[F],
    programs: Programs[F],
    queues: Queues[F],
    maybeDataApplication: Option[BaseDataApplicationL0Service[F]],
    config: AppConfig,
    hasherSelector: HasherSelector[F]
  ): F[Unit] = {
    val pddConfig = config.peerDiscovery.delay
    val peerDiscoveryDelay = SelectablePeerDiscoveryDelay.make(
      clusterStorage = storages.cluster,
      appEnvironment = config.environment,
      checkPeersAttemptDelay = pddConfig.checkPeersAttemptDelay,
      checkPeersMaxDelay = pddConfig.checkPeersMaxDelay,
      additionalDiscoveryDelay = pddConfig.additionalDiscoveryDelay,
      minPeers = pddConfig.minPeers
    )

    List[Daemon[F]](
      NodeStateDaemon.make(storages.node, services.gossip),
      DownloadDaemon.make(storages.node, programs.download, peerDiscoveryDelay, hasherSelector),
      CurrencySnapshotEventsPublisherDaemon.make(queues.l1Output, services.gossip, maybeDataApplication),
      CollateralDaemon.make(services.collateral, storages.snapshot, storages.cluster)
    ).traverse(_.start).void
  }

}
