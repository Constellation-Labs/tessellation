package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.effect.kernel.{Async, Ref}
import cats.effect.std.Queue
import cats.syntax.all._

import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.consensus.ConsensusFunctions
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.{ConsensusCommand, PendingTriggersF}
import io.constellationnetwork.node.shared.infrastructure.consensus.{FacilitatorSelector, _}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash

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
  // Gossip handle for re-distributing locally-derived consensus artifacts that downstream
  // peers need but might miss via the per-peer assembly path. Currently used to broadcast
  // an assembled `ViewChangeCertificate` from `StateTransitions.checkViewChangeAssembly` so
  // peers that didn't reach quorum locally (gossip lag) still store the VCC and can propose
  // at view > 0 without hitting `vcc_missing_for_view_gt_0`.
  gossip: Gossip[F],
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
  // while this returns true -- evictions during bootstrap caused cascading committee splits in
  // the early fork-recovery E2E failures.
  isInBootstrap: Outcome => Boolean,
  // Binds B1/B2 certs to the current tip. Without this binding a leader
  // could replay an older quorum of signed votes that matched the current facilitators hash but
  // referenced a stale tip, and honest followers would accept the cert. Every cert is now required
  // to carry `lastSnapshotHash == lastSnapshotHashOf(state.lastOutcome)`; mixed-tip vote sets are
  // rejected at build time and the advancer validates the cert's tip at proposal-acceptance time.
  lastSnapshotHashOf: Outcome => Hash,
  // Set of peers currently on B2 probation per the carried outcome. A peer is on probation while
  // its `readmissionCountdown` is positive — it was previously evicted via B1 and is awaiting a
  // quorum-witnessed `AdmissionCertificate` from the cluster before it can re-enter the committee.
  // Recovery (`StateTransitions.initFromDownload`) must respect this set
  // and decline to facilitate while self is still in probation. Otherwise a recovering peer would
  // emit Facility/Proposal/Signature against a committee the cluster has already rebuilt without
  // it, producing a split-brain consensus state where rounds appear stalled at `progress=1/5`
  // forever (gl0-4 in fork-recovery E2E). Same wiring source as `StallDetector`'s B2
  // admission emission — see the ConsensusEventLoop construction site.
  probationPeersOf: Outcome => Set[PeerId],
  // Layer-specific extraction of consensus-agreed peerQuality from the carried outcome.
  // Used to widen the witness pool for B1/B2/VCC cert assembly beyond the round-start
  // committee. peerQuality lives in the concrete outcome type (GlobalConsensusOutcome /
  // CurrencyConsensusOutcome) and is signed as part of the snapshot, so every honest node
  // computes byte-identical maps and therefore the same wider witness pool. See
  // `StateTransitions.witnessPoolFor` for the deterministic derivation.
  //
  // Returns an empty map if the outcome carries no peerQuality (genesis / pre-v8 outcomes),
  // in which case the wider-pool reduces to `eligibleFacilitators` and preserves prior
  // behavior.
  peerQualityOf: Outcome => Map[PeerId, (Int, Int)],
  // Local-only marker: the consensus key at which this node most recently completed
  // `initFromDownload` (recovery path). Read by layer-specific advancers — when this node is
  // elected leader within `recoveryLeaderCooldownRounds` of recovery completion, the advancer
  // should emit a ViewChangeVote instead of attempting to propose, because the just-recovered
  // node's storage / gossip mesh / proposal-build pipeline isn't primed yet (gl0-4 in E2E:
  // recovered, won leader lottery for the next round, wedged the round for 98s on `progress=1/5`
  // before the cluster's stall detector forced the view change). Self-deferred view change makes
  // that wedge a ~5s rotation instead of a 98s timeout.
  //
  // Local-only because the deferral is a self-defense decision, not a consensus rule. Other peers
  // still elect this node deterministically; this node refuses and emits a VCV. The view-change
  // certificate then assembles deterministically across the cluster as designed.
  recoveredAtKeyRef: Ref[F, Option[Key]],
  // Per-key abandonment-retry counter. Owned by `AbandonmentTracker`, which increments on every
  // `ROUND_ABANDONED_RETRIABLE` at the same key and resets when a new key arrives. Read by
  // `ConsensusRoundRunner` at round-facilitation time and fed into the state creator as the
  // initial `viewNumber` argument to `selectLeaderWeighted`. The deterministic effect: each
  // same-key retry picks a different initial leader because `selectLeaderWeighted` indexes
  // `sorted[viewNumber % size]`. Without this, every retry of a wedged key reset view to 0 and
  // re-elected the same silent peer that caused the prior abandonment (observed at
  // ord 3126034: 7 abandons in a row with leader=63adf853, score decaying but never crossing
  // the chronic threshold).
  //
  // Deterministic across honest nodes that observed the same abandonment sequence. Slight
  // divergence is possible if a node joins/restarts mid-wedge, but view-change converges within
  // ~10 seconds of round start when leaders disagree.
  retriableAtSameKeyRef: Ref[F, (Option[Key], Int)]
)

object ConsensusEngineContext {

  def create[F[_]: Async, Event, Key, Artifact, Ctx, Status, Outcome, Kind](
    selfId: PeerId,
    queue: Queue[F, ConsensusCommand[Key, Artifact, Ctx, Outcome]],
    pending: PendingTriggersF[F],
    gossip: Gossip[F],
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
    lastSnapshotHashOf: Outcome => Hash,
    probationPeersOf: Outcome => Set[PeerId],
    peerQualityOf: Outcome => Map[PeerId, (Int, Int)] = (_: Outcome) => Map.empty[PeerId, (Int, Int)]
  ): F[ConsensusEngineContext[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind]] =
    for {
      running <- Ref.of[F, Boolean](false)
      recoveredAtKey <- Ref.of[F, Option[Key]](None)
      retriableAtSameKey <- Ref.of[F, (Option[Key], Int)]((none[Key], 0))
    } yield
      ConsensusEngineContext(
        selfId,
        queue,
        running,
        pending,
        gossip,
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
        peerQualityOf,
        recoveredAtKey,
        retriableAtSameKey
      )
}
