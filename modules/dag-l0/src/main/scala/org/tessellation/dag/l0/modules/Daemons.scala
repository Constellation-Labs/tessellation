package org.tessellation.dag.l0.modules

import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.functor._
import cats.syntax.traverse._

import org.tessellation.dag.l0.config.types.AppConfig
import org.tessellation.dag.l0.infrastructure.snapshot.GlobalSnapshotEventsPublisherDaemon
import org.tessellation.dag.l0.infrastructure.trust.TrustStorageUpdater
import org.tessellation.node.shared.domain.Daemon
import org.tessellation.node.shared.infrastructure.cluster.daemon.NodeStateDaemon
import org.tessellation.node.shared.infrastructure.collateral.daemon.CollateralDaemon
import org.tessellation.node.shared.infrastructure.snapshot.daemon.{DownloadDaemon, SelectablePeerDiscoveryDelay}
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.HasherSelector

object Daemons {

  def start[F[_]: Async: Supervisor](
    storages: Storages[F],
    services: Services[F],
    programs: Programs[F],
    queues: Queues[F],
    nodeId: PeerId,
    cfg: AppConfig,
    hasherSelector: HasherSelector[F]
  ): F[Unit] = {
    val pddCfg = cfg.peerDiscovery.delay
    val peerDiscoveryDelay = SelectablePeerDiscoveryDelay.make(
      clusterStorage = storages.cluster,
      appEnvironment = cfg.environment,
      checkPeersAttemptDelay = pddCfg.checkPeersAttemptDelay,
      checkPeersMaxDelay = pddCfg.checkPeersMaxDelay,
      additionalDiscoveryDelay = pddCfg.additionalDiscoveryDelay,
      minPeers = pddCfg.minPeers
    )

    List[Daemon[F]](
      NodeStateDaemon.make(storages.node, services.gossip),
      DownloadDaemon.make(storages.node, programs.download, peerDiscoveryDelay, hasherSelector),
      Daemon.periodic(storages.trust.updateTrustWithBiases(nodeId), cfg.trust.daemon.interval),
      GlobalSnapshotEventsPublisherDaemon.make(queues.stateChannelOutput, queues.l1Output, services.gossip),
      CollateralDaemon.make(services.collateral, storages.globalSnapshot, storages.cluster),
      TrustStorageUpdater.daemon(services.trustStorageUpdater)
    ).traverse(_.start).void
  }

}
