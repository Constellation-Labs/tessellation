package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.effect.kernel.{Async, Outcome => FiberOutcome}
import cats.effect.std.Random
import cats.effect.syntax.all._
import cats.kernel.Next
import cats.syntax.all._
import cats.{Eq, Show}

import scala.collection.immutable.SortedSet
import scala.concurrent.duration._
import scala.reflect.runtime.universe.TypeTag

import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.{Category, Event => LogEvent}
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.ConsensusCommand._
import io.constellationnetwork.node.shared.infrastructure.consensus.engine._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger._
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics.unsafeLabelName
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.security.HasherSelector
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import io.circe.Encoder
import monocle.Lens

/** Finite State Machine that routes consensus commands to appropriate handlers.
  *
  * ==Why FSM?==
  *
  * Consensus has two states: IDLE (waiting for trigger) and BUSY (round in progress). The same command may need different handling
  * depending on current state:
  *
  * {{{
  *   Command: StartRound(TimeTrigger)
  *
  *   If IDLE: Actually start a new round
  *   If BUSY: Store as pending, start after current round
  * }}}
  *
  * @see
  *   ConsensusRoundRunner for round execution
  * @see
  *   StateTransitions for state management
  * @see
  *   RumorHandler for rumor processing
  */
class ConsensusFSM[F[
  _
]: Async: Metrics: HasherSelector: Random, Event, Key: Eq: Show: Next: TypeTag: Encoder, Artifact: Eq, Ctx: Eq, Status, Outcome: Eq, Kind](
  ctx: ConsensusEngineContext[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind],
  roundRunner: ConsensusRoundRunner[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind]
)(
  implicit outcomeKey: Lens[Outcome, Key],
  outcomeArtifact: Lens[Outcome, Signed[Artifact]],
  outcomeContext: Lens[Outcome, Ctx],
  outcomeTrigger: Lens[Outcome, ConsensusTrigger]
) {

  private val rumorHandler = new RumorHandler[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind](ctx)
  private val transitions = new StateTransitions[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind](ctx)

  def tryAdoptCertifiedOutcome(key: Key): F[Boolean] = transitions.tryAdoptCertifiedOutcome(key)

  import ctx.{isRoundRunning => isRunning, logger => log, nodeStorage, pending, storage}

  /** Node states where consensus rounds must NOT start (recovery / download / leaving in progress). Leaving is included to prevent an
    * infinite tight loop: when a node is in Leaving state, rounds immediately abandon (no peers), try to force-leave (already Leaving →
    * fails), try recovery (not in Ready/Observing → fails), and re-queue TimeTick → creating a CPU-burning spin loop at 21,000+
    * iterations/second.
    *
    * Observing is intentionally NOT blocked: in cluster-wide rollback restart, validators land in Observing until their first round
    * completes (Observing → Ready transition in StateTransitions.scala). Each validator calls startRound locally to create state for the
    * leader's round and send Facility declarations. Blocking startRound in Observing stops them from participating, so the leader stalls at
    * progress=1/N and rounds abandon. The isolated-node recovery bug where TimeTicks start bogus rounds during Observing needs a different
    * fix.
    */
  private val roundBlockedStates: Set[NodeState] =
    Set(NodeState.WaitingForDownload, NodeState.DownloadInProgress, NodeState.Leaving)

  def handle(cmd: ConsensusCommand[Key, Artifact, Ctx, Outcome]): F[Unit] =
    cmd match {
      case ReleaseFirstRoundStart(permit, expectedCommittee) =>
        ctx.firstRoundStartGate.releaseAfter(permit)(establishRecoveryFirstRound(permit, expectedCommittee)).flatMap {
          case true =>
            Async[F]
              .start(
                (ConsensusLog.info(
                  log,
                  Category.Recovery,
                  permit.key.show,
                  "n/a",
                  LogEvent.RollbackQuorumFeasible,
                  "reason" -> "first_round_start_gate_released",
                  "generation" -> permit.generation.toString,
                  "committeeSize" -> expectedCommittee.size.toString
                ) >> Metrics[F].updateGauge("dag_consensus_first_round_start_gate_held", 0L) >>
                  Metrics[F].incrementCounter("dag_consensus_first_round_start_gate_released_total")).attempt.void
              )
              .attempt
              .void
          case false =>
            Async[F]
              .start(
                (ConsensusLog.warn(
                  log,
                  Category.Recovery,
                  permit.key.show,
                  "n/a",
                  LogEvent.RollbackFirstRoundDeferred,
                  "reason" -> "stale_first_round_start_gate_release",
                  "generation" -> permit.generation.toString
                ) >> Metrics[F].incrementCounter("dag_consensus_first_round_start_gate_stale_release_total")).attempt.void
              )
              .attempt
              .void
        }
      case _ =>
        Metrics[F]
          .incrementCounter(
            "dag_consensus_fsm_command_processed",
            Seq(unsafeLabelName("command_type") -> cmd.getClass.getSimpleName.stripSuffix("$"))
          )
          .attempt
          .void >> (cmd match {
          case startCommand if FirstRoundStartGate.isOrdinaryStartCommand(startCommand) =>
            ctx.firstRoundStartGate.isHeld.flatMap {
              case true =>
                (Metrics[F].updateGauge("dag_consensus_first_round_start_gate_held", 1L) >>
                  Metrics[F].incrementCounter(
                    "dag_consensus_first_round_start_gate_trigger_dropped_total",
                    Seq(unsafeLabelName("trigger_type") -> cmd.getClass.getSimpleName.stripSuffix("$"))
                  )).attempt.void
              case false => handleUngated(cmd)
            }
          case _ => handleUngated(cmd)
        })
    }

  private def handleUngated(cmd: ConsensusCommand[Key, Artifact, Ctx, Outcome]): F[Unit] =
    isRunning.get.flatMap { running =>
      cmd match {
        case RumorReceived(r)                     => rumorHandler.process(r)
        case CheckUpdate(key)                     => transitions.checkUpdate(key)
        case CheckViewChangeAssembly(key)         => transitions.checkViewChangeAssembly(key)
        case CheckViewChangeApply(key, from, to)  => transitions.checkViewChangeApply(key, from, to)
        case CheckTimeoutCertificateAssembly(key) => transitions.checkTimeoutCertificateAssembly(key)
        case CheckTimeoutCertificateApply(key, from, to) =>
          transitions.checkTimeoutCertificateApply(key, from, to)
        case CheckEvictionAssembly(key, target)  => transitions.checkEvictionAssembly(key, target)
        case CheckAdmissionAssembly(key, target) => transitions.checkAdmissionAssembly(key, target)
        case RestartAfterSoftReset(key)          => restartAfterSoftReset(key, running)
        case _: ReleaseFirstRoundStart[_]        => Async[F].unit
        case InternalScheduled(inner)            => handle(inner)
        case PeerObserved(peer)                  => transitions.registerPeer(peer)

        case _ if running => handleWhileBusy(cmd)
        case _            => handleWhileIdle(cmd)
      }
    }

  /** The operator-plan gate opens only after the exact planned round is actually established on this serialized FSM. If establishment fails
    * or is cancelled, retain any deterministic partial state for the next attempt, but reset local runner bookkeeping so the same
    * generation can resume it. Ordinary queued triggers remain gated throughout.
    */
  private def establishRecoveryFirstRound(
    permit: FirstRoundStartGate.Permit[Key],
    expectedCommittee: SortedSet[io.constellationnetwork.schema.peer.PeerId]
  ): F[Unit] = {
    val nextKey = permit.key.next
    val cleanupFailedAttempt =
      roundRunner.cleanupRound.attempt.void >>
        pending.clear().attempt.void >>
        isRunning.set(false).attempt.void

    ConsensusFSM.establishFirstRound(
      parent = permit.key,
      expectedCommittee = expectedCommittee,
      runningBefore = isRunning.get,
      start = startRoundUngated(TimeTrigger.some),
      inspect = (isRunning.get, storage.getState(nextKey)).mapN { (running, state) =>
        ConsensusFSM.FirstRoundInspection(
          running,
          state.map(s => outcomeKey.get(s.lastOutcome)),
          state.map(s => SortedSet.from(s.roundStartFacilitators.value))
        )
      },
      cleanupFailure = cleanupFailedAttempt
    )
  }

  private def handleWhileIdle(cmd: ConsensusCommand[Key, Artifact, Ctx, Outcome]): F[Unit] =
    cmd match {
      case StartRound(trigger) => startRound(trigger)
      case TimeTick            => startRound(Some(TimeTrigger))
      case FacilitateByEvent   => startRound(Some(EventTrigger))
      case RoundCompleted(_)   => log.warn(ConsensusLog.format(Category.Lifecycle, "n/a", "n/a", LogEvent.IdleRoundCompleted))
      case ConsensusFinished(_, _, _) =>
        log.warn(ConsensusLog.format(Category.Lifecycle, "n/a", "n/a", LogEvent.IdleConsensusFinished))
      case InitializeFromDownload(key, art, c, isRecovery) =>
        transitions.initFromDownload(key, art, c, isRecovery)
      case InitializeFromRollback(key, outcome, startPolicy) => transitions.initFromRollback(key, outcome, startPolicy)
      case WithdrawFromConsensus                             => transitions.withdraw
      case _                                                 => Async[F].unit
    }

  private def handleWhileBusy(cmd: ConsensusCommand[Key, Artifact, Ctx, Outcome]): F[Unit] =
    cmd match {
      case FacilitateByEvent =>
        Metrics[F].incrementCounter("dag_consensus_fsm_pending_deferred", Seq(unsafeLabelName("trigger_type") -> "event")) >>
          pending.setEvent()
      case TimeTick =>
        Metrics[F].incrementCounter("dag_consensus_fsm_pending_deferred", Seq(unsafeLabelName("trigger_type") -> "time")) >>
          pending.setTime()
      case StartRound(Some(TimeTrigger)) =>
        Metrics[F].incrementCounter("dag_consensus_fsm_pending_deferred", Seq(unsafeLabelName("trigger_type") -> "time")) >>
          pending.setTime()
      case StartRound(_) =>
        Metrics[F].incrementCounter("dag_consensus_fsm_pending_deferred", Seq(unsafeLabelName("trigger_type") -> "event")) >>
          pending.setEvent()
      case RoundCompleted(expectedAttemptId) =>
        // Drop stale RoundCompleted if the round advanced since this command was queued. Prevents
        // the Bug A race where an abandonment-queued RoundCompleted fires AFTER a view change has
        // moved the round to CollectingSignatures view=1, wiping the nearly-finished round.
        // `None` (unconditional) preserves force-completion paths in ConsensusEventLoop's error handler.
        storage.getRoundAttemptId.flatMap { currentId =>
          if (expectedAttemptId.exists(_ != currentId))
            ConsensusLog.info(
              log,
              Category.Lifecycle,
              "n/a",
              "n/a",
              LogEvent.RoundCompletedNoOutcome,
              "reason" -> "stale_attempt_id",
              "expected" -> expectedAttemptId.map(_.toString).getOrElse("none"),
              "current" -> currentId.toString,
              "action" -> "dropped"
            ) >> Metrics[F].incrementCounter("dag_consensus_round_completed_stale_dropped")
          else
            completeRound(log.debug(ConsensusLog.format(Category.Lifecycle, "n/a", "n/a", LogEvent.RoundCompletedNoOutcome)))
        }
      case ConsensusFinished(key, _, trigger) =>
        completeRound(
          log.info(ConsensusLog.format(Category.Lifecycle, key.toString, "n/a", LogEvent.ConsensusFinished)) >>
            roundRunner.afterConsensusFinish(trigger)
        )
      case WithdrawFromConsensus                    => pending.setEvent()
      case cmd @ InitializeFromDownload(_, _, _, _) =>
        // Two cases:
        // (1) A genuinely-in-progress round is finishing right before recovery's
        //     InitializeFromDownload arrives — it will emit ConsensusFinished shortly
        //     and the re-queue below will resolve it.
        // (2) The round is STALE: it was running when the node entered recovery (e.g.
        //     fork-divergence triggered WaitingForDownload while round N was in its
        //     CollectingFacilities phase), and no ConsensusFinished / RoundCompleted
        //     will ever be emitted for it because the recovery path resets storage
        //     beneath it. Without intervention, the FSM stays Busy forever and
        //     InitializeFromDownload re-queues indefinitely (observed in fork-recovery
        //     E2E: re-queue every 1s for 15+ minutes, node never rejoins).
        //
        // Distinguishing signal: by the time InitializeFromDownload arrives, the
        // recovery download + observe phases are complete and the node is in the
        // Observing state (post-recovery). An in-flight round at that point cannot
        // legitimately progress — its facilitator set is stale, its peer declarations
        // were for a key the storage no longer tracks. So we force-complete to
        // transition the FSM Busy -> Idle and clear pending triggers to avoid
        // immediately starting a fresh (also-doomed) round before recovery concludes.
        nodeStorage.getNodeState.flatMap { state =>
          if (state === NodeState.Observing) {
            ConsensusLog.warn(
              log,
              Category.Lifecycle,
              "n/a",
              "n/a",
              LogEvent.ForcedRoundCompletionOnRecovery,
              "reason" -> "InitializeFromDownload received while Busy in Observing; stale round blocking recovery"
            ) >>
              Metrics[F].incrementCounter("dag_consensus_forced_complete_on_recovery_init") >>
              pending.clear() >>
              completeRound(Async[F].unit) >>
              ctx.queue.offer(cmd)
          } else {
            // Fire-and-forget re-queue with a longer delay. The old approach
            // (100ms blocking sleep) caused priority inversion: the sleep ran inside
            // evalMap, blocking the command stream and preventing
            // ConsensusFinished/RoundCompleted from draining — the very commands that
            // would free the FSM from Busy state.
            log.info("[CONSENSUS:RECOVERY] InitializeFromDownload received while busy, re-queuing in 1s") >>
              Async[F].start(Async[F].sleep(1.second) >> ctx.queue.offer(cmd)).void
          }
        }
      case _ => Async[F].unit
    }

  private def startRound(trigger: Option[ConsensusTrigger]): F[Unit] =
    ctx.firstRoundStartGate.isHeld.ifM(
      Metrics[F]
        .incrementCounter(
          "dag_consensus_first_round_start_gate_trigger_dropped_total",
          Seq(unsafeLabelName("trigger_type") -> trigger.fold("none")(_.toString))
        )
        .attempt
        .void,
      startRoundUngated(trigger)
    )

  /** Enforce the recovery hold at the actual state-creation boundary. Command dispatch also filters ordinary triggers for observability,
    * but direct callers (round completion and soft-reset retry) must not bypass a newly re-armed gate.
    */
  private def startRoundUngated(trigger: Option[ConsensusTrigger]): F[Unit] =
    isRunning.get.ifM(
      ifTrue = log.debug(s"Ignoring StartRound($trigger) — round already running"),
      ifFalse = {
        val startAttempt = nodeStorage.getNodeState.flatMap { state =>
          if (!roundBlockedStates.contains(state))
            (log.info(
              ConsensusLog.format(
                Category.Lifecycle,
                "n/a",
                "n/a",
                LogEvent.FsmRoundStart,
                "trigger" -> trigger.map(_.toString).getOrElse("none")
              )
            ) >>
              Metrics[F].incrementCounter(
                "dag_consensus_fsm_round_started",
                Seq(unsafeLabelName("trigger_type") -> trigger.map(_.toString).getOrElse("none"))
              ) >>
              Metrics[F].updateGauge("dag_consensus_fsm_round_running", 1)).attempt.void >>
              isRunning.set(true) >>
              roundRunner.runRound(trigger)
          else
            (ConsensusLog.warn(
              log,
              Category.Lifecycle,
              "n/a",
              "n/a",
              LogEvent.RoundBlockedByState,
              "nodeState" -> state.show,
              "trigger" -> trigger.map(_.toString).getOrElse("none")
            ) >> Metrics[F].incrementCounter("dag_consensus_round_blocked_by_state")).attempt.void
        }

        startAttempt.handleErrorWith { error =>
          ConsensusFSM.recoverRoundStartFailure(
            cleanup = roundRunner.cleanupRound,
            markIdle = isRunning.set(false),
            observe = log.error(error)(s"Round start failed for trigger=$trigger; retaining state and retrying"),
            pause = Async[F].sleep(1.second),
            enqueueRetry = ctx.queue.offer(StartRound(trigger))
          )
        }
      }
    )

  private def completeRound(preAction: F[Unit]): F[Unit] =
    for {
      // Clean up round-scoped fibers (stall detector, etc.) before post-consensus scheduling.
      // Note: the timer fiber from scheduleTimeTrigger is NOT tracked (uses supervisor.supervise
      // directly) so it survives cleanup — this prevents the deadlock where a subsequent round's
      // cleanup would cancel the timer before it fires.
      _ <- roundRunner.cleanupRound
      _ <- preAction
      _ <- isRunning.set(false)
      _ <- (Metrics[F].incrementCounter("dag_consensus_fsm_round_completed") >>
        Metrics[F].updateGauge("dag_consensus_fsm_round_running", 0)).attempt.void
      // Direct invocation instead of queue roundtrip — isRunning is already false,
      // so startRound will proceed immediately without an extra queue poll interval.
      next <- pending.pullNext
      _ <- next.traverse_ {
        case TriggerPriority.Time  => startRound(Some(TimeTrigger))
        case TriggerPriority.Event => startRound(Some(EventTrigger))
      }
    } yield ()

  /** A soft reset clears ConsensusStorage while this FSM is still BUSY. A normal StartRound would therefore only set a pending trigger and
    * wait for a RoundCompleted that can no longer be produced. Handle the reset as one serialized transition: clear stale pending triggers,
    * move BUSY -> IDLE when needed, then immediately create the round again from the latest persisted outcome.
    *
    * If another queued declaration already rebuilt state for the key, the restart is stale and is ignored rather than wiping that progress.
    */
  private def restartAfterSoftReset(key: Key, wasRunning: Boolean): F[Unit] =
    storage.getState(key).flatMap {
      case Some(_) =>
        ConsensusLog.warn(
          log,
          Category.Recovery,
          key.show,
          "n/a",
          LogEvent.SoftResetSuppressed,
          "reason" -> "restart_command_stale_state_present",
          "action" -> "ignored"
        ) >> Metrics[F].incrementCounter("dag_consensus_soft_reset_restart_stale_total")
      case None =>
        val complete =
          if (wasRunning) pending.clear() >> completeRound(Async[F].unit)
          else Async[F].unit

        complete >>
          (ConsensusLog.warn(
            log,
            Category.Recovery,
            key.show,
            "n/a",
            LogEvent.SoftResetTriggered,
            "action" -> "restart_round",
            "wasRunning" -> wasRunning.toString
          ) >>
            Metrics[F].incrementCounter("dag_consensus_soft_reset_restart_total")).attempt.void >>
          startRound(None)
    }
}

object ConsensusFSM {

  private[consensus] final case class FirstRoundInspection[Key](
    running: Boolean,
    parent: Option[Key],
    committee: Option[SortedSet[io.constellationnetwork.schema.peer.PeerId]]
  )

  /** Establish-and-inspect boundary shared by the production FSM and fault-injection tests. The caller's gate remains held around this
    * effect. Success therefore means the exact N+1 state is installed and the runner is Busy before the gate can become Open; every other
    * result cleans local runner bookkeeping and leaves the same permit retryable.
    */
  private[consensus] def establishFirstRound[F[_]: Async, Key: Eq: Show](
    parent: Key,
    expectedCommittee: SortedSet[io.constellationnetwork.schema.peer.PeerId],
    runningBefore: F[Boolean],
    start: F[Unit],
    inspect: F[FirstRoundInspection[Key]],
    cleanupFailure: F[Unit]
  ): F[Unit] = {
    val establish =
      runningBefore.flatMap {
        case true =>
          new IllegalStateException(s"Recovery first round already Busy before establishment for parent=${parent.show}")
            .raiseError[F, Unit]
        case false => start
      } >> inspect.flatMap {
        case FirstRoundInspection(true, Some(actualParent), Some(actualCommittee))
            if actualParent === parent && actualCommittee === expectedCommittee =>
          Async[F].unit
        case observation =>
          new IllegalStateException(
            s"Recovery first round was not established for parent=${parent.show}: " +
              s"running=${observation.running} statePresent=${observation.parent.nonEmpty} " +
              s"expectedCommittee=${expectedCommittee.size} actualCommittee=${observation.committee.fold("none")(_.size.toString)}"
          ).raiseError[F, Unit]
      }

    establish.guaranteeCase {
      case FiberOutcome.Succeeded(_) => Async[F].unit
      case _                         => cleanupFailure
    }
  }

  /** A partially-created round is resumable: ConsensusRoundRunner recognizes an existing state and re-arms its monitor/initial check. On
    * any start failure, clean only local runner fibers, mark the FSM Idle, and enqueue the same trigger after a bounded pause. The recovery
    * is uncancelable so the sole wake-up cannot be lost between consuming a soft-reset command and publishing its retry.
    */
  private[consensus] def recoverRoundStartFailure[F[_]: Async](
    cleanup: F[Unit],
    markIdle: F[Unit],
    observe: F[Unit],
    pause: F[Unit],
    enqueueRetry: F[Unit]
  ): F[Unit] =
    Async[F].uncancelable { _ =>
      cleanup.attempt.void >> markIdle >> observe.attempt.void >> pause >> enqueueRetry
    }
}
