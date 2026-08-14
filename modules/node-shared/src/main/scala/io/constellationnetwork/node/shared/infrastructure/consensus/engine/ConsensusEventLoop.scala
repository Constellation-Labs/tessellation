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

  /** Queue-depth telemetry is not part of command semantics. In particular, a metrics failure after `queue.take` must not consume the only
    * generation-bound command that can release a recovery gate.
    */
  private[consensus] def observeQueueDepthThenDispatch[F[_]: Async](observeQueueDepth: F[Unit])(dispatch: F[Unit]): F[Unit] =
    observeQueueDepth.attempt.void >> dispatch

  /** Keep a queued pacemaker request failure from terminating the sole consensus command stream. The recovery effects are each isolated:
    * logging/metrics failure must not prevent the monitor from being re-armed, and a monitor-rearm failure must not escape and kill the
    * stream either.
    */
  private[consensus] def recoverViewChangeRequestFailure[F[_]: Async](
    request: F[Unit],
    onError: Throwable => F[Unit],
    rearmMonitor: F[Unit]
  ): F[Unit] =
    request.handleErrorWith(error => onError(error).attempt.void >> rearmMonitor.attempt.void)

  /** Always attempt monitor re-arm after an abandonment drains. A real abandon removed the state, so re-arm is a no-op; a stale/safety
    * suppression keeps the state and needs a new monitor. All error handling is best-effort so this helper cannot terminate the sole
    * command stream.
    */
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
    * monitor re-arm is the safe recovery; an unconditional `RoundCompleted` there could erase a newer attempt. Once state is absent, an
    * unconditional completion releases Busy, and Ready nodes receive a TimeTick so the serialized loop can start a fresh round. Recovery
    * states deliberately do not receive a tick: DownloadDaemon owns their next transition.
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
      case Right(true)  => Async[F].unit
      case Right(false) =>
        // The original failure may have happened immediately after state=None and before
        // `clearResourcesPreservingDeclarations`. Retry that idempotent, policy-aware cleanup so
        // the next attempt cannot inherit stale Proposal/Signature/Binary slots.
        clearAttemptResources.attempt.void >> offerRoundCompleted.attempt.void >>
          nodeState.attempt.flatMap {
            case Right(NodeState.Ready) => offerTimeTick.attempt.void
            case _                      => Async[F].unit
          }
      case Left(_) => Async[F].unit
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

  /** A signed-plan initialization is idempotent for its exact installed outcome and gate generation. It may therefore resume from any
    * lifecycle state reached by the non-transactional initialization tail, rather than bouncing through a fresh download and losing the
    * only barrier installer.
    */
  private[consensus] def plannedInitializationRetryableState(state: NodeState): Boolean =
    state === NodeState.Observing || state === NodeState.WaitingForReady || state === NodeState.Ready

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
    openAdmissionCadenceOf: Key => Boolean,
    // Optional local proof view for the exact next-seat finality-headroom gate. Global L0
    // supplies parent snapshot proof signers; Currency L0 deliberately leaves this absent
    // because unanimity cannot prove an unseated (n + 1)th signer.
    locallyObservedParentSignersOf: Outcome => Option[Set[PeerId]],
    lastSnapshotHashOf: Outcome => Hash,
    peerQualityOf: Outcome => Map[PeerId, (Int, Int)],
    lastOutcomeEndTimeMsOf: Outcome => Option[Long],
    getPeerChainTips: F[Map[PeerId, ChainTip]],
    // Optional local-only, lane-typed readiness probes. Global L0 keeps open admission
    // Ready-only while allowing carried probation peers to prove an exact tip before Ready;
    // Currency L0 leaves direct probing disabled.
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
    onOutcomePreInitialize: Option[Outcome => F[Unit]] = None,
    initiallyHoldFirstRound: Boolean = false,
    plannedRecoveryCommittee: Option[F[Option[SortedSet[PeerId]]]] = None
  )(
    implicit _key: monocle.Lens[Outcome, Key],
    _context: monocle.Lens[Outcome, Ctx],
    _artifact: monocle.Lens[Outcome, Signed[Artifact]],
    _trigger: monocle.Lens[Outcome, ConsensusTrigger]
  ): F[BuiltConsensusLoop[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind]] =
    for {
      queue <- injectedQueue.fold(Queue.unbounded[F, ConsensusCommand[Key, Artifact, Ctx, Outcome]])(_.pure[F])
      pending <- PendingTriggers.create[F]
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
        onOutcomePreInitialize.getOrElse((_: Outcome) => Async[F].unit),
        initiallyHoldFirstRound,
        plannedRecoveryCommittee.getOrElse(none[SortedSet[PeerId]].pure[F])
      )
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
        openAdmissionCadenceOf,
        locallyObservedParentSignersOf,
        lastSnapshotHashOf,
        getPeerChainTips,
        admissionCandidateTipProbe,
        healthRef,
        b2AtTipStreakRef
      )
      roundFibersRef <- Ref.of[F, List[Fiber[F, Throwable, Unit]]](Nil)
      cancelSignalRef <- Ref.of[F, Option[Deferred[F, Unit]]](None)
      roundRunner = new ConsensusRoundRunner[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind](
        ctx,
        stallDetector,
        roundFibersRef,
        cancelSignalRef
      )
      fsm = new ConsensusFSM[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind](ctx, roundRunner)
      manager <- ConsensusManager.make[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind](
        queue,
        storage,
        nodeStorage
      )
    } yield {

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
                case _: ConsensusCommand.CheckUpdate[_] | ConsensusCommand.TimeTick | ConsensusCommand.FacilitateByEvent |
                    _: ConsensusCommand.StartRound | _: ConsensusCommand.AbandonRound[_] | _: ConsensusCommand.RequestViewChange[_] =>
                  true
                case _ => false
              }
              if (isRecovering && isStaleCommand) {
                (cmd match {
                  case _: ConsensusCommand.RequestViewChange[_] => viewChangeManager.resetAllRequests
                  case _                                        => Async[F].unit
                }) >> ctx.logger.debug(s"Discarding stale ${cmd.getClass.getSimpleName} command: node in $currentState (recovery)")
              } else
                cmd match {
                  case ConsensusCommand.AbandonRound(key, reason, expectedAttemptId, expectedResourceGeneration) =>
                    // #1 lost-update fix: the StallDetector monitor enqueues AbandonRound instead of calling
                    // abandonmentTracker.abandonRound on its own fiber. Handling it here runs abandonRound's
                    // condModifyState on this single command-loop fiber, the only state writer (see
                    // ConsensusStorage.condModifyState). abandonRound re-checks outcome-readiness, so a round
                    // that completed since the monitor's decision is not wiped.
                    containAbandonAndRearm(
                      abandonmentTracker.abandonRound(key, reason, expectedAttemptId, expectedResourceGeneration),
                      err =>
                        ctx.logger.error(err)("Unhandled error processing AbandonRound, recovering") >>
                          Metrics[F].incrementCounter("dag_consensus_command_error"),
                      recoverFailedAbandonAfterStateRemoval(
                        ctx.storage.getState(key).map(_.isDefined),
                        nodeStorage.getNodeState,
                        ctx.storage.clearResourcesPreservingDeclarations(key),
                        queue.offer(ConsensusCommand.RoundCompleted(None)),
                        queue.offer(ConsensusCommand.TimeTick)
                      ),
                      // The monitor exits as soon as it enqueues AbandonRound. If the drain-time
                      // attempt/outcome/vote-lock checks suppress that command, the state remains
                      // live and must receive a replacement monitor. This is a no-op after a real
                      // abandon because performAbandon removed the state.
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
                    (cmd match {
                      // softResetRoundState discards the key's VCV/TC resources, so the
                      // one-emission latch must be released before recreating view 0.
                      // Ordinary abandonment deliberately preserves both resources and latch.
                      case ConsensusCommand.RestartAfterSoftReset(key) => viewChangeManager.resetRequestForKey(key)
                      // Certified completion retires this ordinal's one-emission entries.
                      case ConsensusCommand.ConsensusFinished(key, _, _) => viewChangeManager.resetRequestForKey(key)
                      // Download/rollback clears consensus resources across keys.
                      case _: ConsensusCommand.InitializeFromDownload[_, _, _] | _: ConsensusCommand.InitializeFromRollback[_, _] =>
                        viewChangeManager.resetAllRequests
                      case _ => Async[F].unit
                    }) >> fsm
                      .handle(cmd)
                      .flatTap { _ =>
                        // After a successful consensus round completes, reset recovery counters.
                        // This prevents stale history from causing premature force-leave on future (unrelated) recovery.
                        cmd match {
                          case _: ConsensusCommand.ConsensusFinished[_, _] =>
                            abandonmentTracker.resetOnSuccessfulRound >>
                              // Re-collect registrations from Ready peers in the background.
                              // The peerRegistrationStream only fires on state changes, so peers that
                              // registered before their observation key was set (or whose state change
                              // was missed) never get re-queried. This ensures every Ready peer's
                              // registration is refreshed each round, closing the timing gap.
                              Async[F]
                                .start(
                                  ctx.clusterStorage.getResponsivePeers.flatMap { peers =>
                                    peers
                                      .filter(_.state === NodeState.Ready)
                                      .toList
                                      .traverse_(peer =>
                                        collectRegistration(consensusClient, storage)(peer)
                                          .handleErrorWith(_ => Async[F].unit)
                                      )
                                  }
                                )
                                .void
                          case _ => Async[F].unit
                        }
                      }
                      .handleErrorWith { err =>
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
                                case _: ConsensusCommand.ConsensusFinished[_, _] | _: ConsensusCommand.RoundCompleted =>
                                  // Critical: if round-completion commands fail, FSM stays stuck in BUSY forever.
                                  // Force round completion so the next round can start. Unconditional (no attemptId)
                                  // because this is the error-recovery path — must always proceed.
                                  // Also offer TimeTick ONLY if node is not in Leaving state: the forced RoundCompleted
                                  // calls completeRound without afterConsensusFinish, so no timer is scheduled for the
                                  // next round. On solo nodes with no external events, this would deadlock consensus.
                                  // However, if the node is Leaving, queuing TimeTick creates a tight spin loop
                                  // (rounds immediately abandon, can't force-leave, can't recover, re-queue TimeTick).
                                  ctx.logger.warn("Forcing round completion after failed ConsensusFinished/RoundCompleted") >>
                                    Metrics[F].incrementCounter("dag_consensus_forced_round_completion") >>
                                    queue.offer(ConsensusCommand.RoundCompleted(None)) >>
                                    nodeStorage.getNodeState.flatMap { state =>
                                      if (state =!= NodeState.Leaving)
                                        queue.offer(ConsensusCommand.TimeTick)
                                      else
                                        ctx.logger.warn("Skipping TimeTick after error recovery: node is in Leaving state") >>
                                          Metrics[F].incrementCounter("dag_consensus_timetick_suppressed_leaving")
                                    }
                                case init @ ConsensusCommand.InitializeFromDownload(_, _, _, _) =>
                                  (ctx.plannedRecoveryCommittee.attempt, ctx.firstRoundStartGate.isHeld.attempt).tupled.flatMap {
                                    case (Right(Some(_)), _) | (_, Right(true)) =>
                                      // A planned initialization may already have installed the exact anchor and advanced
                                      // Observing -> WaitingForReady before a later prerequisite failed. Re-entering download would
                                      // strand that state and can never recreate the all-member barrier. Retry the exact idempotent
                                      // initialization until it installs/observes the same outcome and starts the barrier fiber.
                                      (ctx.logger.warn(
                                        "Signed recovery-plan initialization failed after a partial install; retrying the exact generation"
                                      ) >> Metrics[F].incrementCounter(
                                        "dag_consensus_recovery_plan_init_resume_total"
                                      )).attempt.void >>
                                        Async[F]
                                          .start(
                                            Async[F].sleep(1.second) >> nodeStorage.getNodeState.flatMap { state =>
                                              queue.offer(init).whenA(plannedInitializationRetryableState(state))
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
                                      ConsensusCommand.RollbackStartPolicy.RequireAlignedCommittee(_)
                                    ) =>
                                  // The signed recovery plan is fail-closed and idempotent for the
                                  // exact installed anchor. Retry the serialized initialization if an
                                  // operational effect failed before or after its barrier was spawned.
                                  Async[F].start(Async[F].sleep(1.second) >> queue.offer(rollback)).void
                                case _ => Async[F].unit
                              })
                        }
                      }
                }
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
