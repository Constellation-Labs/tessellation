package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.Show
import cats.effect.Fiber
import cats.effect.kernel.{Async, Deferred, Ref}
import cats.effect.std.{Queue, Random, Supervisor}
import cats.kernel.{Eq, Next, Order}
import cats.syntax.all._

import scala.collection.immutable.SortedSet
import scala.concurrent.duration._
import scala.reflect.runtime.universe.TypeTag

import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.consensus.ConsensusFunctions
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger._
import io.constellationnetwork.node.shared.infrastructure.consensus.{FacilitatorSelector, _}
import io.constellationnetwork.node.shared.infrastructure.gossip.event.ChainTip
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.schema.node.{NodeState, NodeStateTransition}
import io.constellationnetwork.schema.peer.{Peer, PeerId}
import io.constellationnetwork.security.HasherSelector
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import fs2.Stream
import io.circe.Encoder
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Builds and wires together all consensus engine components.
  *
  * This is the entry point for creating a consensus engine. It assembles:
  *   - Command queue for FSM events
  *   - FSM instance for command routing
  *   - RoundRunner for facilitation and post-consensus logic
  *   - Background streams for peer registration and node state changes
  *
  * ==Streams==
  *
  * The engine runs three parallel streams:
  *
  * '''commandStream:''' Main event loop - takes commands from queue, routes to FSM
  * {{{
  *   Stream.repeatEval(queue.take).evalMap(fsm.handle)
  * }}}
  *
  * '''peerRegistrationStream:''' Watches for peers entering Observing state, collects their registration info
  *
  * '''leavingStream:''' Watches for node entering Leaving state, triggers withdrawal
  *
  * ==Usage==
  * {{{
  *   val built = ConsensusEventLoop.build(storage, creator, updater, ...)
  *   built.run.compile.drain // Start the engine
  *   built.manager.registerForConsensus(...) // External API
  * }}}
  *
  * @see
  *   ConsensusManager for external API
  * @see
  *   ConsensusFSM for command processing
  */
object ConsensusEventLoop {

  /** Reset node-local admission proof history before a downloaded or rollback outcome is installed.
    *
    * Keeping the reset and the layer callback in one production helper makes their ordering explicit and gives the recovery boundary a
    * narrow regression seam. The history is local vote-emission evidence only; it must never cross an installed-outcome lineage boundary.
    */
  private[consensus] def resetAdmissionProofHistoryBefore[F[_]: Async, Outcome](
    admissionProofHistoryRef: Ref[F, AdmissionProofHistory.History],
    onOutcomePreInitialize: Option[Outcome => F[Unit]]
  )(outcome: Outcome): F[Unit] =
    admissionProofHistoryRef.set(AdmissionProofHistory.History.empty) >>
      onOutcomePreInitialize.fold(Async[F].unit)(_(outcome))

  /** Select the only attempt token that may be used for a delayed CheckUpdate retry.
    *
    * An already-tokenized retry can never be relabeled with a newer epoch. A plain CheckUpdate may snapshot a token only while its key's
    * state owns the current global epoch.
    */
  private[consensus] def checkUpdateRetryAttempt(
    currentAttemptId: Long,
    stateAttemptId: Option[Long],
    statePresent: Boolean,
    retainedAttemptId: Option[Long]
  ): Option[Long] =
    stateAttemptId.filter { stateAttempt =>
      statePresent && stateAttempt === currentAttemptId && retainedAttemptId.forall(_ === stateAttempt)
    }.map(stateAttempt => retainedAttemptId.getOrElse(stateAttempt))

  /** Queue-depth telemetry is not part of command semantics. In particular, a metrics failure after `queue.take` must not consume the only
    * generation-bound command that can release a recovery gate.
    */
  private[consensus] def observeQueueDepthThenDispatch[F[_]: Async](observeQueueDepth: F[Unit])(dispatch: F[Unit]): F[Unit] =
    observeQueueDepth.attempt.void >> dispatch

  private[consensus] def recoverViewChangeRequestFailure[F[_]: Async](
    request: F[Unit],
    onError: Throwable => F[Unit],
    rearmMonitor: F[Unit]
  ): F[Unit] =
    request.handleErrorWith(error => onError(error).attempt.void >> rearmMonitor.attempt.void)

  private[consensus] def containAbandonAndRearm[F[_]: Async](
    abandon: F[Unit],
    onError: Throwable => F[Unit],
    recoverAfterError: F[Unit],
    rearmMonitor: F[Unit]
  ): F[Unit] =
    abandon.attempt.flatMap {
      case Left(error) => onError(error).attempt.void >> recoverAfterError.attempt.void
      case Right(_)    => Async[F].unit
    } >> rearmMonitor.attempt.void

  /** Repair the FSM only when an errored abandon is confirmed to have already removed its round state. If state remains, the caller's
    * monitor re-arm is the safe recovery. Once state is absent, a completion tagged with the post-removal epoch releases Busy, and
    * consensus-participating lifecycle states receive a TimeTick. Download/leaving states remain owned by their lifecycle daemons.
    *
    * Every effect is best-effort because this is the last-resort error path for the sole command stream.
    */
  private[consensus] def recoverFailedAbandonAfterStateRemoval[F[_]: Async](
    stateStillPresent: F[Boolean],
    nodeState: F[NodeState],
    clearAttemptResources: F[Unit],
    offerRoundCompleted: F[Unit],
    offerTimeTick: F[Unit]
  ): F[Unit] =
    stateStillPresent.attempt.flatMap {
      case Right(true) => Async[F].unit
      case Right(false) =>
        clearAttemptResources.attempt.void >> offerRoundCompleted.attempt.void >>
          nodeState.attempt.flatMap {
            case Right(state) if ConsensusFSM.consensusParticipatingState(state) => offerTimeTick.attempt.void
            case _                                                               => Async[F].unit
          }
      case Left(_) => Async[F].unit
    }

  private[consensus] sealed trait SoftResetRestartFailureDisposition extends Product with Serializable
  private[consensus] object SoftResetRestartFailureDisposition {
    case object ReleaseAbsentState extends SoftResetRestartFailureDisposition
    case object PreservePresentState extends SoftResetRestartFailureDisposition
    case object RetryStateProbe extends SoftResetRestartFailureDisposition
  }

  /** State, rather than command type alone, decides how a failed soft-reset restart is repaired.
    *
    * A present state may contain declarations accepted after the reset command was queued and must never be force-completed. An absent
    * state cannot produce its own `RoundCompleted`, so the serialized FSM must be released explicitly. An inconclusive state read
    * authorizes neither mutation; retrying the same idempotent restart after a bounded delay is the only safe action.
    */
  private[consensus] def softResetRestartFailureDisposition(
    stateProbe: Either[Throwable, Boolean]
  ): SoftResetRestartFailureDisposition =
    stateProbe match {
      case Right(false) => SoftResetRestartFailureDisposition.ReleaseAbsentState
      case Right(true)  => SoftResetRestartFailureDisposition.PreservePresentState
      case Left(_)      => SoftResetRestartFailureDisposition.RetryStateProbe
    }

  /** Recover a failed `RestartAfterSoftReset` without blindly treating every failure as a completed round.
    *
    * When the key is absent, all cleanup operations are idempotent and independently contained so one failed tail cannot prevent the
    * unconditional completion from releasing a Busy FSM. Ready, Observing, and WaitingForReady are consensus-participating lifecycle states
    * and receive the replacement trigger; download/leaving states remain owned by their lifecycle daemons. When state exists, preserve it
    * and re-arm monitoring. If the probe itself fails, perform no cleanup and schedule the exact restart command for a bounded retry.
    */
  private[consensus] def recoverRestartAfterSoftResetFailure[F[_]: Async](
    restartStillCurrent: F[Boolean],
    stateStillPresent: F[Boolean],
    cleanupRound: F[Unit],
    clearPending: F[Unit],
    clearAttemptResources: F[Unit],
    offerRoundCompleted: F[Unit],
    nodeState: F[NodeState],
    offerTimeTick: F[Unit],
    ensureMonitor: F[Unit],
    requeueAfterProbeFailure: F[Unit]
  ): F[Unit] =
    restartStillCurrent.attempt.flatMap {
      case Left(_)      => requeueAfterProbeFailure.attempt.void
      case Right(false) => Async[F].unit
      case Right(true) =>
        stateStillPresent.attempt.flatMap { observed =>
          softResetRestartFailureDisposition(observed) match {
            case SoftResetRestartFailureDisposition.ReleaseAbsentState =>
              cleanupRound.attempt.void >>
                clearPending.attempt.void >>
                clearAttemptResources.attempt.void >>
                offerRoundCompleted.attempt.flatMap {
                  case Left(_) => requeueAfterProbeFailure.attempt.void
                  case Right(_) =>
                    nodeState.attempt.flatMap {
                      case Right(state) if ConsensusFSM.consensusParticipatingState(state) =>
                        offerTimeTick.attempt.flatMap {
                          case Left(_)  => requeueAfterProbeFailure.attempt.void
                          case Right(_) => Async[F].unit
                        }
                      case Right(_) => Async[F].unit
                      case Left(_)  => requeueAfterProbeFailure.attempt.void
                    }
                }

            case SoftResetRestartFailureDisposition.PreservePresentState =>
              ensureMonitor.attempt.void

            case SoftResetRestartFailureDisposition.RetryStateProbe =>
              requeueAfterProbeFailure.attempt.void
          }
        }
    }

  private[consensus] sealed trait InitDownloadFailureDisposition extends Product with Serializable
  private[consensus] object InitDownloadFailureDisposition {
    case object HoldObservingAndRetry extends InitDownloadFailureDisposition
    case object RestartDownload extends InitDownloadFailureDisposition
  }

  private[consensus] def initDownloadFailureDisposition(
    error: Throwable,
    hasDirectProbationProbe: Boolean
  ): InitDownloadFailureDisposition =
    error match {
      case _: StateTransitions.SelfStillInProbation if hasDirectProbationProbe =>
        InitDownloadFailureDisposition.HoldObservingAndRetry
      case _ => InitDownloadFailureDisposition.RestartDownload
    }

  private[consensus] def shouldRequeueProbationInitialization(
    disposition: InitDownloadFailureDisposition,
    state: NodeState
  ): Boolean =
    disposition == InitDownloadFailureDisposition.HoldObservingAndRetry && state === NodeState.Observing

  /** A first-round-aligned initialization is idempotent for its exact installed outcome and gate generation. It may therefore resume from
    * any lifecycle state reached by the non-transactional initialization tail, rather than bouncing through a fresh download and losing the
    * only barrier installer.
    */
  private[consensus] def recoveryInitializationRetryableState(state: NodeState): Boolean =
    ConsensusFSM.consensusParticipatingState(state)

  final case class BuiltConsensusLoop[F[_], Event, Key, Artifact, Ctx, Status, Outcome, Kind](
    run: Stream[F, Unit],
    manager: ConsensusManager[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind],
    queue: Queue[F, ConsensusCommand[Key, Artifact, Ctx, Outcome]],
    healthRef: Ref[F, ConsensusHealthStatus]
  )

  def build[
    F[_]: Async: HasherSelector: Metrics: Random: Supervisor,
    Event,
    Key: Order: Show: Next: TypeTag: Encoder,
    Artifact: Eq,
    Ctx: Eq,
    Status,
    Outcome: Eq,
    Kind
  ](
    selfId: PeerId,
    gossip: Gossip[F],
    storage: ConsensusStorage[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind],
    stateCreator: ConsensusStateCreator[F, Key, Artifact, Ctx, Status, Outcome, Kind],
    stateUpdater: ConsensusStateUpdater[F, Key, Artifact, Ctx, Status, Outcome, Kind],
    stateAdvancer: ConsensusStateAdvancer[F, Key, Artifact, Ctx, Status, Outcome, Kind],
    stateRemover: ConsensusStateRemover[F, Key, Event, Artifact, Ctx, Status, Outcome, Kind],
    ops: ConsensusOps[Status, Kind],
    nodeStorage: NodeStorage[F],
    clusterStorage: ClusterStorage[F],
    consensusFunctions: ConsensusFunctions[F, Event, Key, Artifact, Ctx],
    consensusClient: ConsensusClient[F, Key, Outcome],
    config: ConsensusConfig,
    facilitatorSelector: FacilitatorSelector,
    peerQualityTracker: PeerQualityTracker[F],
    membershipPolicy: HealthDerivedMembershipPolicy,
    viewChangeVoter: ViewChangeVoter[F, Key],
    timeoutVoter: TimeoutVoter[F, Key],
    evictionVoter: EvictionVoter[F, Key],
    admissionVoter: AdmissionVoter[F, Key],
    isInBootstrap: Outcome => Boolean,
    probationPeersOf: Outcome => Set[PeerId],
    admissionNomineesOf: Outcome => Set[PeerId],
    // Canonical parent-round committee used to reproduce the signing-participation
    // auditor's deterministic target when authorizing a paired atomic admission vote.
    parentRoundCommitteeOf: Outcome => Set[PeerId],
    openAdmissionCadenceOf: Key => Boolean,
    // Optional local proof view for the exact next-seat finality-headroom gate. Global L0 supplies parent snapshot proof signers.
    locallyObservedParentSignersOf: Outcome => Option[Set[PeerId]],
    // Monotonic certified-lineage fact. False only for the exact canonical from-genesis
    // singleton before its first 1 -> 2 expansion.
    expandedBeyondSingletonOf: Outcome => Boolean,
    lastSnapshotHashOf: Outcome => Hash,
    peerQualityOf: Outcome => Map[PeerId, (Int, Int)],
    lastOutcomeEndTimeMsOf: Outcome => Option[Long],
    getPeerChainTips: F[Map[PeerId, ChainTip]],
    // Optional local-only, lane-typed readiness probes. Global L0 keeps open admission Ready-only while allowing carried probation peers to
    // prove an exact tip before Ready.
    admissionCandidateTipProbe: Option[AdmissionCandidateTipProbe.Probes[F]],
    // Layer-supplied HTTP preflight for AbandonmentTracker's rumor-stale escalation shape (issue
    // #1533): does a corroborated group of Ready peers report the same committed snapshot at or
    // above the abandoned key?
    // Wire with `PeersCommittedAheadProbe.make`; see `AbandonmentTracker.EscalationSignal` for
    // why frozen rumor state alone must never escalate.
    peersCommittedAheadProbe: Key => F[AbandonmentTracker.PeersAheadProbe],
    // Optional externally-owned health Ref. When provided, AbandonmentTracker writes to it so
    // a sibling reader (e.g. `Cluster.leave()`'s wedge guard in SharedServices) observes the
    // same wedge signal. When None, an internal Ref is created and writes stay local. Either
    // way the returned `BuiltConsensusLoop.healthRef` is non-null so callers always have a handle.
    injectedHealthRef: Option[Ref[F, ConsensusHealthStatus]] = None,
    // Optional externally-created command queue. Global L0 uses this to enqueue the existing
    // CheckEvictionAssembly command from its round-creation finality audit before the first
    // Facility is sent. Other layers retain the internal-queue behavior.
    injectedQueue: Option[Queue[F, ConsensusCommand[Key, Artifact, Ctx, Outcome]]] = None,
    // Typed persistence hooks keep Global L0 recovery sidecars out of the generic event loop.
    onOutcomeFinalized: Option[Outcome => F[Unit]] = None,
    onOutcomeInitialized: Option[Outcome => F[Unit]] = None,
    onOutcomePreInitialize: Option[Outcome => F[Unit]] = None,
    onOutcomeSafetyInitialized: Option[Outcome => F[Unit]] = None,
    onOutcomeRollbackInitialized: Option[(Outcome, ConsensusCommand.RollbackStartPolicy) => F[Unit]] = None,
    initiallyHoldFirstRound: Boolean = false,
    recoverySeedCommittee: Option[F[Option[SortedSet[PeerId]]]] = None,
    normalFirstRoundAlignment: Option[NormalFirstRoundAlignment[Key, Outcome]] = None
  )(
    implicit _key: monocle.Lens[Outcome, Key],
    _context: monocle.Lens[Outcome, Ctx],
    _artifact: monocle.Lens[Outcome, Signed[Artifact]],
    _trigger: monocle.Lens[Outcome, ConsensusTrigger]
  ): F[BuiltConsensusLoop[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind]] =
    for {
      queue <- injectedQueue.fold(Queue.unbounded[F, ConsensusCommand[Key, Artifact, Ctx, Outcome]])(_.pure[F])
      pending <- PendingTriggers.create[F]
      admissionProofHistoryRef <- Ref.of[F, AdmissionProofHistory.History](AdmissionProofHistory.History.empty)
      preInitialize =
        // Download and rollback are explicit lineage boundaries. Reset the node-local
        // admission evidence before installing either outcome so a recovery cannot inherit
        // headroom observations from the abandoned lineage.
        resetAdmissionProofHistoryBefore(admissionProofHistoryRef, onOutcomePreInitialize) _
      ctx <- ConsensusEngineContext.create(
        selfId,
        queue,
        pending,
        gossip,
        storage,
        stateCreator,
        stateUpdater,
        stateAdvancer,
        stateRemover,
        ops,
        nodeStorage,
        clusterStorage,
        Slf4jLogger.getLogger[F],
        config,
        consensusFunctions,
        consensusClient,
        facilitatorSelector,
        peerQualityTracker,
        membershipPolicy,
        isInBootstrap,
        lastSnapshotHashOf,
        probationPeersOf,
        peerQualityOf,
        _key.get _,
        lastOutcomeEndTimeMsOf,
        onOutcomeFinalized.getOrElse((_: Outcome) => Async[F].unit),
        onOutcomeInitialized.getOrElse((_: Outcome) => Async[F].unit),
        preInitialize,
        onOutcomeSafetyInitialized.getOrElse((_: Outcome) => Async[F].unit),
        onOutcomeRollbackInitialized.getOrElse((_: Outcome, _: ConsensusCommand.RollbackStartPolicy) => Async[F].unit),
        initiallyHoldFirstRound,
        recoverySeedCommittee.getOrElse(none[SortedSet[PeerId]].pure[F]),
        normalFirstRoundAlignment
      )
      _ <- Metrics[F].updateGauge("dag_consensus_first_round_start_gate_held", if (initiallyHoldFirstRound) 1L else 0L)
      healthRef <- injectedHealthRef.fold(ConsensusHealthStatus.ref[F])(Async[F].pure)
      requestedViewChanges <- Ref.of[F, Set[ViewChangeManager.RequestId[Key]]](Set.empty)
      viewChangeManager = new ViewChangeManager[F, Key, Artifact, Ctx, Status, Outcome, Kind](
        storage,
        peerQualityTracker,
        queue,
        Slf4jLogger.getLogger[F],
        viewChangeVoter,
        timeoutVoter,
        state => stateAdvancer.getConsensusOutcome(state).isDefined,
        requestedViewChanges
      )
      abandonmentTracker = new AbandonmentTracker[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind](
        ctx,
        healthRef,
        peersCommittedAheadProbe
      )
      b2AtTipStreakRef <- Ref.of[F, Map[PeerId, Int]](Map.empty)
      stallDetector = new StallDetector[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind](
        ctx,
        viewChangeManager,
        abandonmentTracker,
        evictionVoter,
        admissionVoter,
        probationPeersOf,
        admissionNomineesOf,
        parentRoundCommitteeOf,
        openAdmissionCadenceOf,
        locallyObservedParentSignersOf,
        expandedBeyondSingletonOf,
        lastSnapshotHashOf,
        getPeerChainTips,
        admissionCandidateTipProbe,
        healthRef,
        b2AtTipStreakRef,
        admissionProofHistoryRef
      )
      roundFibersRef <- Ref.of[F, List[Fiber[F, Throwable, Unit]]](Nil)
      cancelSignalRef <- Ref.of[F, Option[Deferred[F, Unit]]](None)
      roundRunner = new ConsensusRoundRunner[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind](
        ctx,
        stallDetector,
        roundFibersRef,
        cancelSignalRef
      )
      onConsensusFinishedAccepted = (key: Key, _: Outcome) =>
        // These are local post-completion maintenance effects. Keep them behind the
        // token-validated FSM completion boundary and independently best-effort; none may
        // reinterpret an already-consumed finish command as a failed completion.
        viewChangeManager.resetRequestForKey(key).attempt.void >>
          abandonmentTracker.resetOnSuccessfulRound.attempt.void >>
          Async[F]
            .start(
              ctx.clusterStorage.getResponsivePeers.flatMap { peers =>
                peers
                  .filter(_.state === NodeState.Ready)
                  .toList
                  .traverse_(peer => collectRegistration(consensusClient, storage)(peer).handleErrorWith(_ => Async[F].unit))
              }
            )
            .attempt
            .void
      fsm = new ConsensusFSM[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind](ctx, roundRunner, onConsensusFinishedAccepted)
      manager <- ConsensusManager.make[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind](
        queue,
        storage,
        nodeStorage
      )
    } yield {

      def scheduleCheckUpdateRetry(key: Key, retainedAttemptId: Option[Long]): F[Unit] =
        (ctx.storage.getRoundAttemptId, ctx.storage.getStateAttemptId(key), ctx.storage.getState(key)).tupled.flatMap {
          case (currentAttemptId, stateAttemptId, state) =>
            // A RetryCheckUpdate keeps its original token. A plain CheckUpdate snapshots the
            // token only when this key still owns the current global state epoch. Never
            // re-tokenize a delayed old-key retry with a newer round's epoch.
            ConsensusEventLoop
              .checkUpdateRetryAttempt(currentAttemptId, stateAttemptId, state.nonEmpty, retainedAttemptId)
              .traverse_ { attemptId =>
                Async[F]
                  .start(Async[F].sleep(1.second) >> queue.offer(ConsensusCommand.RetryCheckUpdate(key, attemptId)))
                  .attempt
                  .void
              }
        }

      val commandStream: Stream[F, Unit] =
        Stream.repeatEval(queue.take).evalMap { cmd =>
          observeQueueDepthThenDispatch(queue.size.flatMap(sz => Metrics[F].updateGauge("dag_consensus_command_queue_size", sz))) {
            nodeStorage.getNodeState.flatMap { currentState =>
              // Skip stale consensus commands during recovery. The node may have transitioned to
              // WaitingForDownload/DownloadInProgress/Observing while gossip/stall declarations
              // from the previous round are still arriving. Processing them hits cleared caches
              // and can crash the event loop, preventing InitializeFromDownload from being dequeued.
              // Note: Observing is intentionally excluded — initFromDownload processes commands
              // while in Observing state (fetching outcomes from peers). Filtering commands
              // during Observing can leave the node stuck with no path to WaitingForReady.
              val isRecovering = currentState === NodeState.WaitingForDownload ||
                currentState === NodeState.DownloadInProgress ||
                currentState === NodeState.WaitingForObserving
              val isStaleCommand = cmd match {
                // Note: ConsensusFinished and RoundCompleted are internal FSM state transitions
                // (Busy→Idle) and must NEVER be filtered — dropping them leaves the FSM permanently
                // stuck in Busy, causing InitializeFromDownload to re-queue forever.
                case _: ConsensusCommand.CheckUpdate[_] | ConsensusCommand.TimeTick |
                    ConsensusCommand.FacilitateByEvent | _: ConsensusCommand.RetryCheckUpdate[_] |
                    _: ConsensusCommand.StartRound | _: ConsensusCommand.AbandonRound[_] | _: ConsensusCommand.RequestViewChange[_] =>
                  true
                case _ => false
              }
              if (isRecovering && isStaleCommand) {
                ((cmd match {
                  case _: ConsensusCommand.RequestViewChange[_] => viewChangeManager.resetAllRequests
                  case _                                        => Async[F].unit
                }) >> ctx.logger.debug(
                  s"Discarding stale ${cmd.getClass.getSimpleName} command: node in $currentState (recovery)"
                )).attempt.void
              } else
                cmd match {
                  case ConsensusCommand.AbandonRound(key, reason, expectedAttemptId, expectedResourceGeneration) =>
                    containAbandonAndRearm(
                      // A certified sidecar is stronger than a local abandon decision. Retain
                      // this v35 recovery path before the rc.8 epoch-bound cleanup.
                      fsm.tryAdoptCertifiedOutcome(key).flatMap { adopted =>
                        if (adopted) Async[F].unit
                        else abandonmentTracker.abandonRound(key, reason, expectedAttemptId, expectedResourceGeneration)
                      },
                      err =>
                        ctx.logger.error(err)("Unhandled error processing AbandonRound, recovering") >>
                          Metrics[F].incrementCounter("dag_consensus_command_error"),
                      recoverFailedAbandonAfterStateRemoval(
                        ctx.storage.getState(key).map(_.isDefined),
                        nodeStorage.getNodeState,
                        // AbandonmentTracker now clears resources inside condModifyState before the state removal commits. If the state is
                        // absent here cleanup already succeeded with the exact round's ViewSafetyMode; re-deriving it after removal would
                        // cross the activation boundary incorrectly.
                        Async[F].unit,
                        ctx.storage.getRoundAttemptId.flatMap(id => queue.offer(ConsensusCommand.RoundCompleted(id))),
                        queue.offer(ConsensusCommand.TimeTick)
                      ),
                      roundRunner.ensureRoundMonitor(key)
                    )
                  case ConsensusCommand.RequestViewChange(
                        key,
                        expectedFromView,
                        expectedAttemptId,
                        expectedProgressGeneration,
                        reason
                      ) =>
                    recoverViewChangeRequestFailure(
                      viewChangeManager.emitRequestedViewChange(
                        key,
                        expectedFromView,
                        expectedAttemptId,
                        expectedProgressGeneration,
                        reason
                      ),
                      err =>
                        ctx.logger.error(err)(s"Unhandled error processing RequestViewChange for key=$key, recovering") >>
                          Metrics[F].incrementCounter("dag_consensus_command_error"),
                      roundRunner.ensureRoundMonitor(key)
                    )
                  case _ =>
                    // Pre-dispatch is deliberately mutation-free. Command-local state is
                    // changed only after the FSM has validated attempt and lineage tokens.
                    fsm.handle(cmd).handleErrorWith { err =>
                      (err, cmd) match {
                        case (probationError, init @ ConsensusCommand.InitializeFromDownload(_, _, _, _))
                            if initDownloadFailureDisposition(probationError, admissionCandidateTipProbe.nonEmpty) ==
                              InitDownloadFailureDisposition.HoldObservingAndRetry =>
                          // This is an expected B2 lifecycle wait, not a failed download. Keep
                          // Observing stable so the authenticated probation chain-tip probe can
                          // reach this peer; do not consume recovery attempts or bounce it
                          // through WaitingForDownload. Re-fetch the outcome after backoff until
                          // a certified admission clears probation.
                          ctx.logger.info("InitializeFromDownload deferred by carried probation; retrying in 1s") >>
                            Metrics[F].incrementCounter("dag_consensus_init_download_probation_deferred_total") >>
                            Async[F]
                              .start(
                                Async[F].sleep(1.second) >> nodeStorage.getNodeState.flatMap { state =>
                                  queue
                                    .offer(init)
                                    .whenA(
                                      shouldRequeueProbationInitialization(
                                        InitDownloadFailureDisposition.HoldObservingAndRetry,
                                        state
                                      )
                                    )
                                }
                              )
                              .void

                        case _ =>
                          (ctx.logger.error(err)(s"Unhandled error processing ${cmd.getClass.getSimpleName}, recovering") >>
                            Metrics[F].incrementCounter("dag_consensus_command_error")).attempt.void >>
                            (cmd match {
                              case restart @ ConsensusCommand.RestartAfterSoftReset(key, expectedAttemptId) =>
                                recoverRestartAfterSoftResetFailure(
                                  (ctx.storage.getRoundAttemptId, ctx.storage.getLastConsensusOutcome).mapN {
                                    case (currentAttemptId, lastOutcome) =>
                                      currentAttemptId === expectedAttemptId &&
                                      lastOutcome.exists(outcome => _key.get(outcome).next === key)
                                  },
                                  ctx.storage.getState(key).map(_.isDefined),
                                  roundRunner.cleanupRound,
                                  pending.clear(),
                                  // The soft reset cleared resources while the exact round state
                                  // (and therefore its v35 safety mode) was still available. If the
                                  // state is absent here, re-deriving a mode is impossible and a
                                  // second cleanup could erase the certified vote lock.
                                  Async[F].unit,
                                  queue.offer(ConsensusCommand.RoundCompleted(expectedAttemptId)),
                                  nodeStorage.getNodeState,
                                  queue.offer(ConsensusCommand.TimeTick),
                                  roundRunner.ensureRoundMonitor(key),
                                  Async[F]
                                    .start(Async[F].sleep(1.second) >> queue.offer(restart))
                                    .void
                                )
                              case finish: ConsensusCommand.ConsensusFinished[_, _] =>
                                // The completion primitive is total after token validation. A
                                // transient validation/storage read failure retries the same token;
                                // it never manufactures an unconditional completion.
                                Async[F].start(Async[F].sleep(1.second) >> queue.offer(finish)).attempt.void
                              case completed: ConsensusCommand.RoundCompleted =>
                                Async[F].start(Async[F].sleep(1.second) >> queue.offer(completed)).attempt.void
                              case ConsensusCommand.CheckUpdate(key) =>
                                scheduleCheckUpdateRetry(key, none)
                              case ConsensusCommand.RetryCheckUpdate(key, expectedAttemptId) =>
                                scheduleCheckUpdateRetry(key, expectedAttemptId.some)
                              case init @ ConsensusCommand.InitializeFromDownload(_, _, _, _) =>
                                (ctx.recoverySeedCommittee.attempt, ctx.firstRoundStartGate.isHeld.attempt).tupled.flatMap {
                                  case (recoverySeed, held) if recoverySeed.exists(_.nonEmpty) || held.contains(true) =>
                                    val explicitRecovery = recoverySeed.exists(_.nonEmpty)
                                    val message =
                                      if (explicitRecovery)
                                        "Operator recovery-seed initialization failed after a partial install; retrying the exact generation"
                                      else
                                        "Normal first-round alignment initialization failed after a partial install; retrying the exact generation"
                                    val observeResume =
                                      ctx.logger.warn(message) >>
                                        (if (explicitRecovery)
                                           Metrics[F].incrementCounter("dag_consensus_recovery_seed_init_resume_total")
                                         else
                                           Metrics[F].incrementCounter(
                                             "dag_consensus_normal_first_round_alignment_init_resume_total"
                                           ))

                                    // An aligned first-round initialization may already have installed the exact anchor and advanced
                                    // Observing -> WaitingForReady before a later prerequisite failed. Re-entering download would
                                    // strand that state and can never recreate its barrier. Retry the exact idempotent initialization
                                    // until it installs/observes the same outcome and starts the barrier fiber.
                                    observeResume.attempt.void >>
                                      Async[F]
                                        .start(
                                          Async[F].sleep(1.second) >> nodeStorage.getNodeState.flatMap { state =>
                                            queue.offer(init).whenA(recoveryInitializationRetryableState(state))
                                          }
                                        )
                                        .attempt
                                        .void

                                  case _ =>
                                    // After 20 retries, ordinary initFromDownload exhausts its retry policy and the error propagates
                                    // here. Transition back to WaitingForDownload so the DownloadDaemon can retry with fresh state.
                                    ctx.logger
                                      .error(err)(
                                        "InitializeFromDownload failed after exhausting retries, triggering recovery download"
                                      ) >>
                                      Metrics[F].incrementCounter("dag_consensus_init_download_failure") >>
                                      abandonmentTracker.trackInitFromDownloadFailure >>
                                      nodeStorage.tryModifyStateGetResult(NodeState.Observing, NodeState.WaitingForDownload).flatMap {
                                        case NodeStateTransition.Success =>
                                          ctx.logger.info(
                                            "Recovery: transitioned Observing → WaitingForDownload for DownloadDaemon retry"
                                          )
                                        case _ =>
                                          // May already be in a different state; try from Ready as well
                                          nodeStorage.tryModifyStateGetResult(NodeState.Ready, NodeState.WaitingForDownload).void
                                      }
                                }
                              case rollback @ ConsensusCommand.InitializeFromRollback(
                                    _,
                                    _,
                                    ConsensusCommand.RollbackStartPolicy.RequireAlignedCommittee(_) |
                                    ConsensusCommand.RollbackStartPolicy.RequireOutcomeAlignedQuorum(_)
                                  ) =>
                                // Aligned rollback initialization is fail-closed and idempotent for
                                // the exact installed anchor. Retry the serialized initialization if
                                // an operational effect failed before or after its barrier was spawned.
                                Async[F].start(Async[F].sleep(1.second) >> queue.offer(rollback)).void
                              case _ => Async[F].unit
                            })
                      }
                    }
                }
            }.handleErrorWith { err =>
              // `queue.take` already consumed the command. A lifecycle-state read/logging
              // failure outside the command-specific handler must not terminate the sole
              // stream or lose its token; every local command is idempotent at the FSM/storage
              // boundary, so retry the exact command after a bounded delay.
              (ctx.logger.error(err)(s"Consensus dispatch failed before completing ${cmd.getClass.getSimpleName}; retrying") >>
                Metrics[F].incrementCounter("dag_consensus_dispatch_outer_error_total")).attempt.void >>
                Async[F].start(Async[F].sleep(1.second) >> queue.offer(cmd)).attempt.void
            }
          }
        }

      // Register peers when they enter Observing, WaitingForReady, or Ready.
      // Observing: earliest opportunity (observationKeyR may not be set yet).
      // WaitingForReady: after initFromDownload sets observationKeyR (reliable).
      // Ready: after first round completes (fallback if earlier attempts missed).
      // collectRegistration retries once at Observing; the later state triggers
      // provide additional chances without relying solely on the retry delay.
      val registrationStates: Set[NodeState] =
        Set(NodeState.Observing, NodeState.WaitingForReady, NodeState.Ready)

      val peerRegistrationStream: Stream[F, Unit] =
        clusterStorage.peerChanges
          .mapFilter(_.right.filter(p => registrationStates.contains(p.state)))
          .filter(_.isResponsive)
          .evalMap(peer =>
            collectRegistration(consensusClient, storage)(peer).handleErrorWith(e => ctx.logger.error(e)("Peer registration failed"))
          )

      val leavingStream: Stream[F, Unit] =
        nodeStorage.nodeStates
          .filter(_ === NodeState.Leaving)
          .evalMap(_ => manager.withdrawFromConsensus.handleErrorWith(e => ctx.logger.error(e)("Error handling Leaving state")))

      val run: Stream[F, Unit] =
        Stream(commandStream, peerRegistrationStream, leavingStream).parJoinUnbounded

      BuiltConsensusLoop(run, manager, queue, healthRef)
    }

  private def collectRegistration[F[_]: Async: Metrics, Event, Key, Artifact, Ctx, Status, Outcome, Kind](
    consensusClient: ConsensusClient[F, Key, Outcome],
    storage: ConsensusStorage[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind]
  )(peer: Peer): F[Unit] = {
    def attempt: F[Boolean] =
      consensusClient.getRegistration.run(peer).flatMap { reg =>
        reg.maybeKey
          .traverse_(key =>
            storage.registerPeer(peer.id, key) >>
              Metrics[F].incrementCounter("dag_consensus_peer_registered")
          )
          .as(reg.maybeKey.isDefined)
      }

    // The peer enters Observing before initFromDownload sets its observationKey.
    // Without a retry, the registration silently fails (None) and the peer never
    // joins the facilitator set. One retry after a short delay covers the gap.
    attempt.ifM(
      ifTrue = Async[F].unit,
      ifFalse = Async[F].sleep(3.seconds) >> attempt.void
    )
  }
}
