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
  // Layer boundary for health-derived membership removal. Global L0 retains signing
  // leases; Currency L0 preserves the legacy automatic-removal behavior.
  membershipPolicy: HealthDerivedMembershipPolicy,
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
  // its `readmissionCountdown` map entry exists, including at sticky zero — it was previously evicted via B1 and is awaiting a
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
  // Layer-specific extraction of the consensus-agreed Key of the carried outcome (the
  // last finalized snapshot ordinal). Used by `StallDetector`'s alpha.98 round-start
  // feasibility check: a peer whose locally-observed tip is < `lastOutcomeKeyOf(state.lastOutcome)`
  // is more than one ordinal behind us and is not "current/useful" as a facilitator for
  // this round. Local guard only -- decision does not mutate the round committee or the
  // facilitator hash, so determinism is preserved.
  lastOutcomeKeyOf: Outcome => Key,
  // Layer-specific extraction of the consensus-agreed end timestamp for the carried outcome.
  // The timestamp is used only as pacemaker evidence: once the local clock observes that the
  // current view has exceeded the parent outcome's end-time + viewInterval budget,
  // `StallDetector` emits a signed ViewChangeVote. It does not seed `ConsensusState.viewNumber`
  // directly; view advancement still requires quorum assembly into a VCC.
  lastOutcomeEndTimeMsOf: Outcome => Option[Long],
  // Layer-owned durable companions to the in-memory outcome. Hooks run only after the
  // corresponding storage transition succeeds and are failure-isolated by StateTransitions:
  // losing a sidecar must never lose a finalized snapshot or prevent recovery initialization.
  onOutcomeFinalized: Outcome => F[Unit],
  onOutcomeInitialized: Outcome => F[Unit],
  // Explicit rollback is the only initialization path allowed to discard safety records above
  // the accepted boundary. Ordinary download/restart initialization must retain an in-flight
  // next-key vote lock, otherwise a process restart re-opens the cross-view double-vote window.
  onOutcomeRollbackInitialized: Outcome => F[Unit],
  // Local-only marker: the consensus key at which this node most recently completed
  // `initFromDownload` (recovery path). Set by `StateTransitions.initFromDownload`.
  //
  // History: was previously read by `StallDetector` to self-yield (emit a VCV) when this
  // node was elected leader within `recoveryLeaderCooldownRounds` of recovery completion,
  // targeting a ~98s wedge on the recently-recovered leader's first round. Removed in
  // alpha.96: the local self-yield advanced this node's view ahead of peers that came up
  // via different state-machine paths (e.g. Rollback vs Download), producing a leader
  // split-brain where two peers each treated themselves as the elected leader, signed
  // different artifacts (artifact hash bakes in viewNumber), and rejected each other's
  // signatures as invalid. Observed on testnet alpha.95 at ord 3127144: round wedged
  // 1h+ at `signatures=1/2` with "Removed 1 invalid signatures" on the wrong-view follower.
  //
  // The marker is still set on each download completion so a future reintroduction that
  // makes the cooldown deterministic across the committee (e.g. by carrying recoveredAtKey
  // on-chain as part of the outcome, then filtering it in `selectLeaderWeighted` input)
  // does not need to plumb new state. Until then this field is write-only.
  recoveredAtKeyRef: Ref[F, Option[Key]],
  // Per-key abandonment-retry counter. Owned by `AbandonmentTracker`, which increments on every
  // `ROUND_ABANDONED_RETRIABLE` at the same key and resets when a new key arrives. This is
  // diagnostic/local liveness state only. It must not seed proposal-critical `viewNumber` or
  // leader selection; alpha.104 showed nodes can restart the same key with different local retry
  // counts and then emit non-coalescing VCVs from different views.
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
    membershipPolicy: HealthDerivedMembershipPolicy,
    isInBootstrap: Outcome => Boolean,
    lastSnapshotHashOf: Outcome => Hash,
    probationPeersOf: Outcome => Set[PeerId],
    peerQualityOf: Outcome => Map[PeerId, (Int, Int)] = (_: Outcome) => Map.empty[PeerId, (Int, Int)],
    lastOutcomeKeyOf: Outcome => Key,
    lastOutcomeEndTimeMsOf: Outcome => Option[Long],
    onOutcomeFinalized: Outcome => F[Unit],
    onOutcomeInitialized: Outcome => F[Unit],
    onOutcomeRollbackInitialized: Outcome => F[Unit]
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
        membershipPolicy,
        isInBootstrap,
        lastSnapshotHashOf,
        probationPeersOf,
        peerQualityOf,
        lastOutcomeKeyOf,
        lastOutcomeEndTimeMsOf,
        onOutcomeFinalized,
        onOutcomeInitialized,
        onOutcomeRollbackInitialized,
        recoveredAtKey,
        retriableAtSameKey
      )
}
