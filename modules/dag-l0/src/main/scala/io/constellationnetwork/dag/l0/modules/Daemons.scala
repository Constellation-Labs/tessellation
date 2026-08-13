package io.constellationnetwork.dag.l0.modules

import java.security.KeyPair

import cats.effect.Async
import cats.effect.kernel.Ref
import cats.effect.std.Supervisor
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.dag.l0.config.types.AppConfig
import io.constellationnetwork.dag.l0.infrastructure.snapshot.GlobalSnapshotEventsPublisherDaemon
import io.constellationnetwork.dag.l0.infrastructure.snapshot.event.GlobalSnapshotEvent
import io.constellationnetwork.dag.l0.infrastructure.trust.TrustStorageUpdater
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.domain.Daemon
import io.constellationnetwork.node.shared.infrastructure.cluster.daemon.NodeStateDaemon
import io.constellationnetwork.node.shared.infrastructure.collateral.daemon.CollateralDaemon
import io.constellationnetwork.node.shared.infrastructure.gossip.event.EventGossipDaemon
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.snapshot.daemon.{DownloadDaemon, SelectablePeerDiscoveryDelay}
import io.constellationnetwork.schema.mpt.GlobalStateKey
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.{HasherSelector, SecurityProvider}

object Daemons {

  def start[F[_]: Async: Supervisor: HasherSelector: SecurityProvider: Metrics, R <: CliMethod](
    storages: Storages[F],
    services: Services[F, R],
    programs: Programs[F],
    queues: Queues[F],
    nodeId: PeerId,
    keyPair: KeyPair,
    cfg: AppConfig,
    hasherSelector: HasherSelector[F],
    eventGossipDaemon: EventGossipDaemon[F, GlobalSnapshotEvent, GlobalStateKey],
    // SharedServices-owned Ref. NodeStateDaemon writes the monotonic timestamp of each
    // observed transition; Cluster.leave()'s dwell-time guard reads it through the thunk
    // installed in SharedServices.make.
    stateEntryAtRef: Ref[F, FiniteDuration]
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
      NodeStateDaemon.make(storages.node, services.gossip, stateEntryAtRef = Some(stateEntryAtRef)),
      DownloadDaemon.make(storages.node, programs.download, peerDiscoveryDelay, hasherSelector),
      Daemon.periodic(storages.trust.updateTrustWithBiases(nodeId), cfg.trust.daemon.interval),
      GlobalSnapshotEventsPublisherDaemon
        .make(
          queues.stateChannelOutput,
          queues.l1Output,
          queues.l1AllowSpendOutput,
          queues.l1TokenLockOutput,
          queues.updateNodeParametersOutput,
          queues.delegatedStakeOutput,
          queues.nodeCollateralOutput,
          keyPair,
          services.eventMempool,
          eventGossipDaemon,
          services.consensus.triggerEventConsensus,
          services.consensus.storage.getLastConsensusOutcome.map(
            _.fold(0) { outcome =>
              val facilitators = outcome.facilitators.value.toSet
              val proofSigners = outcome.finished.signedMajorityArtifact.proofs.toSortedSet.toList.map(_.id.toPeerId).toSet
              GlobalSnapshotEventsPublisherDaemon.participatingFacilitatorCount(facilitators, proofSigners)
            }
          ),
          cfg.snapshot.consensus
        ),
      CollateralDaemon.make(services.collateral, storages.globalSnapshot, storages.cluster),
      TrustStorageUpdater.daemon(services.trustStorageUpdater),
      Daemon.spawn(eventGossipDaemon.start)
    ).traverse(_.start).void
  }

}
