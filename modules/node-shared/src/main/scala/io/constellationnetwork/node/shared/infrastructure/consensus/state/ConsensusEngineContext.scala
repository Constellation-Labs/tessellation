package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.effect.kernel.{Async, Ref}
import cats.effect.std.Queue
import cats.syntax.all._

import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.consensus.ConsensusFunctions
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.{ConsensusCommand, PendingTriggersF}
import io.constellationnetwork.node.shared.infrastructure.consensus.{FacilitatorSelector, _}
import io.constellationnetwork.schema.peer.PeerId

import org.typelevel.log4cats.SelfAwareStructuredLogger

/** Shared context containing all dependencies for consensus components.
  *
  * ==Why This Exists==
  *
  * Consensus involves many components that need access to shared resources:
  *   - Storage for state and declarations
  *   - Creator/Updater/Advancer for state management
  *   - Gossip for spreading declarations
  *   - Logger, metrics, config
  *
  * Instead of passing 15+ parameters to each class, we bundle them in a context.
  *
  * ==Contents==
  *
  * '''Core State Management:'''
  *   - `storage` - ConsensusStorage for persisting state
  *   - `creator` - Creates new consensus states
  *   - `updater` - Updates existing states
  *   - `advancer` - Advances status and extracts outcomes
  *   - `remover` - Handles withdrawal
  *
  * '''Infrastructure:'''
  *   - `queue` - Command queue for FSM
  *   - `pending` - Pending triggers tracker
  *   - `nodeStorage` - Node state management
  *   - `clusterStorage` - Cluster peer information
  *
  * '''Utilities:'''
  *   - `logger` - Logging
  *   - `config` - Timeouts and intervals
  *   - `ops` - Status-specific operations
  */
final case class ConsensusEngineContext[F[_], Event, Key, Artifact, Context, Status, Outcome, Kind](
  selfId: PeerId,
  queue: Queue[F, ConsensusCommand],
  isRoundRunning: Ref[F, Boolean],
  pending: PendingTriggersF[F],
  storage: ConsensusStorage[F, Event, Key, Artifact, Context, Status, Outcome, Kind],
  creator: ConsensusStateCreator[F, Key, Artifact, Context, Status, Outcome, Kind],
  updater: ConsensusStateUpdater[F, Key, Artifact, Context, Status, Outcome, Kind],
  advancer: ConsensusStateAdvancer[F, Key, Artifact, Context, Status, Outcome, Kind],
  remover: ConsensusStateRemover[F, Key, Event, Artifact, Context, Status, Outcome, Kind],
  ops: ConsensusOps[Status, Kind],
  nodeStorage: NodeStorage[F],
  clusterStorage: ClusterStorage[F],
  logger: SelfAwareStructuredLogger[F],
  config: ConsensusConfig,
  fns: ConsensusFunctions[F, Event, Key, Artifact, Context],
  consensusClient: ConsensusClient[F, Key, Outcome],
  facilitatorSelector: FacilitatorSelector,
  peerQualityTracker: PeerQualityTracker[F],
  // Phase B1 gate: returns true while the cluster has not yet produced a snapshot with committee
  // size >= config.bootstrapCompleteProofsThreshold (matches Phase 4's warmup-for-penalty-accrual).
  // All B1 activity (emission, cert assembly, validation, embedding, application) is suppressed
  // while this returns true — evictions during bootstrap caused cascading committee splits in
  // the 2026-04-21 E2E failures.
  isInBootstrap: Outcome => Boolean
)

object ConsensusEngineContext {

  def create[F[_]: Async, Event, Key, Artifact, Ctx, Status, Outcome, Kind](
    selfId: PeerId,
    queue: Queue[F, ConsensusCommand],
    pending: PendingTriggersF[F],
    storage: ConsensusStorage[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind],
    creator: ConsensusStateCreator[F, Key, Artifact, Ctx, Status, Outcome, Kind],
    updater: ConsensusStateUpdater[F, Key, Artifact, Ctx, Status, Outcome, Kind],
    advancer: ConsensusStateAdvancer[F, Key, Artifact, Ctx, Status, Outcome, Kind],
    remover: ConsensusStateRemover[F, Key, Event, Artifact, Ctx, Status, Outcome, Kind],
    ops: ConsensusOps[Status, Kind],
    nodeStorage: NodeStorage[F],
    clusterStorage: ClusterStorage[F],
    logger: SelfAwareStructuredLogger[F],
    config: ConsensusConfig,
    fns: ConsensusFunctions[F, Event, Key, Artifact, Ctx],
    consensusClient: ConsensusClient[F, Key, Outcome],
    facilitatorSelector: FacilitatorSelector,
    peerQualityTracker: PeerQualityTracker[F],
    isInBootstrap: Outcome => Boolean
  ): F[ConsensusEngineContext[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind]] =
    for {
      running <- Ref.of[F, Boolean](false)
    } yield
      ConsensusEngineContext(
        selfId,
        queue,
        running,
        pending,
        storage,
        creator,
        updater,
        advancer,
        remover,
        ops,
        nodeStorage,
        clusterStorage,
        logger,
        config,
        fns,
        consensusClient,
        facilitatorSelector,
        peerQualityTracker,
        isInBootstrap
      )
}
