package io.constellationnetwork.currency.l0.modules

import java.security.KeyPair

import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.functor._
import cats.syntax.traverse._

import io.constellationnetwork.currency.dataApplication.BaseDataApplicationL0Service
import io.constellationnetwork.currency.l0.cli.method.Run
import io.constellationnetwork.currency.l0.config.types.AppConfig
import io.constellationnetwork.currency.l0.snapshot.CurrencySnapshotEventsPublisherDaemon
import io.constellationnetwork.currency.schema.CurrencyStateKey
import io.constellationnetwork.node.shared.domain.Daemon
import io.constellationnetwork.node.shared.infrastructure.cluster.daemon.NodeStateDaemon
import io.constellationnetwork.node.shared.infrastructure.collateral.daemon.CollateralDaemon
import io.constellationnetwork.node.shared.infrastructure.gossip.event.EventGossipDaemon
import io.constellationnetwork.node.shared.infrastructure.snapshot.daemon.{DownloadDaemon, SelectablePeerDiscoveryDelay}
import io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotEvent
import io.constellationnetwork.security.{HasherSelector, SecurityProvider}

object Daemons {

  def start[F[_]: Async: Supervisor: HasherSelector: SecurityProvider](
    storages: Storages[F],
    services: Services[F, Run],
    programs: Programs[F],
    queues: Queues[F],
    maybeDataApplication: Option[BaseDataApplicationL0Service[F]],
    config: AppConfig,
    hasherSelector: HasherSelector[F],
    keyPair: KeyPair,
    eventGossipDaemon: EventGossipDaemon[F, CurrencySnapshotEvent, CurrencyStateKey]
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
      CurrencySnapshotEventsPublisherDaemon.make(
        queues.l1Output,
        maybeDataApplication,
        keyPair,
        services.consensus.eventMempool,
        eventGossipDaemon,
        services.consensus.consensusFns.triggerPredicate,
        services.consensus.manager.triggerEventConsensus
      ),
      CollateralDaemon.make(services.collateral, storages.snapshot, storages.cluster)
    ).traverse(_.start).void
  }

}
