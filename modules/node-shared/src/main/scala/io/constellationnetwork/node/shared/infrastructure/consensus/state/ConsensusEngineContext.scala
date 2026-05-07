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
  queue: Queue[F, ConsensusCommand[Key, Artifact, Context, Outcome]],
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
  isInBootstrap: Outcome => Boolean,
  // Binds B1/B2 certs to the current tip. Codex review 2026-04-23: without this binding a leader
  // could replay an older quorum of signed votes that matched the current facilitators hash but
  // referenced a stale tip, and honest followers would accept the cert. Every cert is now required
  // to carry `lastSnapshotHash == lastSnapshotHashOf(state.lastOutcome)`; mixed-tip vote sets are
  // rejected at build time and the advancer validates the cert's tip at proposal-acceptance time.
  lastSnapshotHashOf: Outcome => io.constellationnetwork.security.hash.Hash,
  // Set of peers currently on B2 probation per the carried outcome. A peer is on probation while
  // its `readmissionCountdown` is positive — it was previously evicted via B1 and is awaiting a
  // quorum-witnessed `AdmissionCertificate` from the cluster before it can re-enter the committee.
  // Codex review 2026-04-24: recovery (`StateTransitions.initFromDownload`) must respect this set
  // and decline to facilitate while self is still in probation. Otherwise a recovering peer would
  // emit Facility/Proposal/Signature against a committee the cluster has already rebuilt without
  // it, producing a split-brain consensus state where rounds appear stalled at `progress=1/5`
  // forever (gl0-4 2026-04-24 fork-recovery E2E). Same wiring source as `StallDetector`'s B2
  // admission emission — see the ConsensusEventLoop construction site.
  probationPeersOf: Outcome => Set[PeerId],
  // Local-only marker: the consensus key at which this node most recently completed
  // `initFromDownload` (recovery path). Read by layer-specific advancers — when this node is
  // elected leader within `recoveryLeaderCooldownRounds` of recovery completion, the advancer
  // should emit a ViewChangeVote instead of attempting to propose, because the just-recovered
  // node's storage / gossip mesh / proposal-build pipeline isn't primed yet (gl0-4 2026-04-27 E2E:
  // recovered, won leader lottery for the next round, wedged the round for 98s on `progress=1/5`
  // before the cluster's stall detector forced the view change). Self-deferred view change makes
  // that wedge a ~5s rotation instead of a 98s timeout.
  //
  // Local-only because the deferral is a self-defense decision, not a consensus rule. Other peers
  // still elect this node deterministically; this node refuses and emits a VCV. The view-change
  // certificate then assembles deterministically across the cluster as designed.
  recoveredAtKeyRef: Ref[F, Option[Key]],
  // Broadcasts an assembled EvictionCertificate to the current facilitator set the moment it is
  // assembled, so the cert reaches all peers via gossip rather than only via the next Proposal's
  // embedded `evictionCertificates` field. Storage-level fan-out only — does NOT change committee
  // selection or any consensus decision in this PR. Read-side consumers (`getAssembledEvictionCertificates`)
  // are unchanged. See docs/consensus/eviction-cert-deterministic-shrinkage.md for the followup
  // (PR2) that would actually use the wider distribution to drive same-ordinal committee shrinkage
  // under a deterministic activation gate.
  evictionCertificateGossiper: io.constellationnetwork.node.shared.infrastructure.consensus.engine.EvictionCertificateGossiper[F, Key]
)

object ConsensusEngineContext {

  def create[F[_]: Async, Event, Key, Artifact, Ctx, Status, Outcome, Kind](
    selfId: PeerId,
    queue: Queue[F, ConsensusCommand[Key, Artifact, Ctx, Outcome]],
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
    isInBootstrap: Outcome => Boolean,
    lastSnapshotHashOf: Outcome => io.constellationnetwork.security.hash.Hash,
    probationPeersOf: Outcome => Set[PeerId],
    evictionCertificateGossiper: io.constellationnetwork.node.shared.infrastructure.consensus.engine.EvictionCertificateGossiper[F, Key]
  ): F[ConsensusEngineContext[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind]] =
    for {
      running <- Ref.of[F, Boolean](false)
      recoveredAtKey <- Ref.of[F, Option[Key]](None)
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
        isInBootstrap,
        lastSnapshotHashOf,
        probationPeersOf,
        recoveredAtKey,
        evictionCertificateGossiper
      )
}
