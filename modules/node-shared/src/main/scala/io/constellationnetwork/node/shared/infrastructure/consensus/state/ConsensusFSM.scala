package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.effect.kernel.Async
import cats.effect.std.Random
import cats.syntax.all._
import cats.{Eq, Show}

import scala.concurrent.duration._

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
class ConsensusFSM[F[_]: Async: Metrics: HasherSelector: Random, Event, Key: Eq: Show, Artifact: Eq, Ctx: Eq, Status, Outcome, Kind](
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

  import ctx.{isRoundRunning => isRunning, logger => log, nodeStorage, pending}

  /** Node states where consensus rounds must NOT start (recovery / download / leaving in progress). Leaving is included to prevent an
    * infinite tight loop: when a node is in Leaving state, rounds immediately abandon (no peers), try to force-leave (already Leaving →
    * fails), try recovery (not in Ready/Observing → fails), and re-queue TimeTick → creating a CPU-burning spin loop at 21,000+
    * iterations/second.
    */
  private val roundBlockedStates: Set[NodeState] =
    Set(NodeState.WaitingForDownload, NodeState.DownloadInProgress, NodeState.Leaving)

  def handle(cmd: ConsensusCommand): F[Unit] =
    Metrics[F].incrementCounter(
      "dag_consensus_fsm_command_processed",
      Seq(unsafeLabelName("command_type") -> cmd.getClass.getSimpleName.stripSuffix("$"))
    ) >>
      isRunning.get.flatMap { running =>
        cmd match {
          case RumorReceived(r)         => rumorHandler.process(r)
          case CheckUpdate(key)         => transitions.checkUpdate(key.asInstanceOf[Key])
          case InternalScheduled(inner) => handle(inner)
          case PeerObserved(peer)       => transitions.registerPeer(peer)
          case IgnoreUnexpectedRumor(r) => log.warn(s"Ignoring unexpected rumor: ${r.getClass.getSimpleName}")

          case _ if running => handleWhileBusy(cmd)
          case _            => handleWhileIdle(cmd)
        }
      }

  private def handleWhileIdle(cmd: ConsensusCommand): F[Unit] =
    cmd match {
      case StartRound(trigger) => startRound(trigger)
      case TimeTick            => startRound(Some(TimeTrigger))
      case FacilitateByEvent   => startRound(Some(EventTrigger))
      case RoundCompleted      => log.warn(ConsensusLog.format(Category.Lifecycle, "n/a", "n/a", LogEvent.IdleRoundCompleted))
      case ConsensusFinished(_, _, _) =>
        log.warn(ConsensusLog.format(Category.Lifecycle, "n/a", "n/a", LogEvent.IdleConsensusFinished))
      case InitializeFromDownload(key, art, c, isRecovery) =>
        transitions.initFromDownload(key.asInstanceOf[Key], art.asInstanceOf[Signed[Artifact]], c.asInstanceOf[Ctx], isRecovery)
      case InitializeFromRollback(key, outcome) => transitions.initFromRollback(key.asInstanceOf[Key], outcome.asInstanceOf[Outcome])
      case WithdrawFromConsensus                => transitions.withdraw
      case _                                    => Async[F].unit
    }

  private def handleWhileBusy(cmd: ConsensusCommand): F[Unit] =
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
      case RoundCompleted =>
        completeRound(log.debug(ConsensusLog.format(Category.Lifecycle, "n/a", "n/a", LogEvent.RoundCompletedNoOutcome)))
      case ConsensusFinished(key, _, trigger) =>
        completeRound(
          log.info(ConsensusLog.format(Category.Lifecycle, key.toString, "n/a", LogEvent.ConsensusFinished)) >>
            roundRunner.afterConsensusFinish(trigger)
        )
      case WithdrawFromConsensus                    => pending.setEvent()
      case cmd @ InitializeFromDownload(_, _, _, _) =>
        // InitializeFromDownload arrived while a stale round is still completing.
        // Fire-and-forget re-queue with a longer delay. The old approach (100ms blocking sleep)
        // caused priority inversion: the sleep ran inside evalMap, blocking the command stream
        // and preventing ConsensusFinished/RoundCompleted from draining — the very commands
        // that would free the FSM from Busy state. With fire-and-forget, the event loop
        // returns immediately so pending commands can be processed.
        log.info("[CONSENSUS:RECOVERY] InitializeFromDownload received while busy, re-queuing in 1s") >>
          Async[F].start(Async[F].sleep(1.second) >> ctx.queue.offer(cmd)).void
      case _ => Async[F].unit
    }

  private def startRound(trigger: Option[ConsensusTrigger]): F[Unit] =
    isRunning.get.ifM(
      ifTrue = log.debug(s"Ignoring StartRound($trigger) — round already running"),
      ifFalse = nodeStorage.getNodeState.flatMap { state =>
        if (!roundBlockedStates.contains(state))
          log.info(
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
            Metrics[F].updateGauge("dag_consensus_fsm_round_running", 1) >>
            isRunning.set(true) >>
            roundRunner.runRound(trigger)
        else
          ConsensusLog.warn(
            log,
            Category.Lifecycle,
            "n/a",
            "n/a",
            LogEvent.RoundBlockedByState,
            "nodeState" -> state.show,
            "trigger" -> trigger.map(_.toString).getOrElse("none")
          ) >>
            Metrics[F].incrementCounter("dag_consensus_round_blocked_by_state")
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
      _ <- Metrics[F].incrementCounter("dag_consensus_fsm_round_completed")
      _ <- Metrics[F].updateGauge("dag_consensus_fsm_round_running", 0)
      _ <- isRunning.set(false)
      // Direct invocation instead of queue roundtrip — isRunning is already false,
      // so startRound will proceed immediately without an extra queue poll interval.
      next <- pending.pullNext
      _ <- next.traverse_ {
        case TriggerPriority.Time  => startRound(Some(TimeTrigger))
        case TriggerPriority.Event => startRound(Some(EventTrigger))
      }
    } yield ()
}
