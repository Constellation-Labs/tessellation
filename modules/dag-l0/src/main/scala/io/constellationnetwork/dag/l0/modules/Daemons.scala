package io.constellationnetwork.dag.l0.modules

import java.security.KeyPair

import cats.effect.Async
import cats.effect.kernel.Ref
import cats.effect.std.Supervisor
import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.dag.l0.config.types.AppConfig
import io.constellationnetwork.dag.l0.infrastructure.snapshot.GlobalSnapshotEventsPublisherDaemon
import io.constellationnetwork.dag.l0.infrastructure.snapshot.event.GlobalSnapshotEvent
import io.constellationnetwork.dag.l0.infrastructure.trust.TrustStorageUpdater
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.domain.Daemon
import io.constellationnetwork.node.shared.infrastructure.cluster.daemon.NodeStateDaemon
import io.constellationnetwork.node.shared.infrastructure.collateral.daemon.CollateralDaemon
import io.constellationnetwork.node.shared.infrastructure.gossip.event.EventGossipDaemon
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.snapshot.daemon.{DownloadDaemon, SelectablePeerDiscoveryDelay}
import io.constellationnetwork.schema.SnapshotOrdinal._
import io.constellationnetwork.schema.mpt.GlobalStateKey
import io.constellationnetwork.schema.node.NodeState
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
    effectiveConsensusConfig: ConsensusConfig,
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
          (
            services.consensus.storage.getLastConsensusOutcome,
            services.consensus.storage.getPeerCurrentKeys,
            storages.cluster.getResponsivePeers
          ).mapN { (maybeOutcome, peerCurrentKeys, responsivePeers) =>
            maybeOutcome.fold(
              GlobalSnapshotEventsPublisherDaemon.EventTriggerContext(
                None,
                0,
                GlobalSnapshotEventsPublisherDaemon.FollowerHeadroom.unavailable
              )
            ) { outcome =>
              val facilitators = outcome.facilitators.value.toSet
              val proofSigners = outcome.finished.signedMajorityArtifact.proofs.toSortedSet.toList.map(_.id.toPeerId).toSet
              val responsivePeerIds = responsivePeers.iterator
                .filterNot(peer => peer.state === NodeState.Leaving || peer.state === NodeState.Offline)
                .map(_.id)
                .toSet

              GlobalSnapshotEventsPublisherDaemon.EventTriggerContext(
                generation = GlobalSnapshotEventsPublisherDaemon
                  .EventTriggerGeneration(outcome.key, outcome.finished.snapshotHash)
                  .some,
                participatingFacilitators = GlobalSnapshotEventsPublisherDaemon.participatingFacilitatorCount(
                  facilitators,
                  proofSigners
                ),
                followerHeadroom = GlobalSnapshotEventsPublisherDaemon.followerHeadroom(
                  outcome.key.next,
                  responsivePeerIds,
                  peerCurrentKeys,
                  nodeId
                )
              )
            }
          },
          effectiveConsensusConfig
        ),
      CollateralDaemon.make(services.collateral, storages.globalSnapshot, storages.cluster),
      TrustStorageUpdater.daemon(services.trustStorageUpdater),
      Daemon.spawn(eventGossipDaemon.start)
    ).traverse(_.start).void
  }

}
