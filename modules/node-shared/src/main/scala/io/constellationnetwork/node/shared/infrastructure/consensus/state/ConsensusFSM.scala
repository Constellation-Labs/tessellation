package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.effect.kernel.Async
import cats.effect.std.Random
import cats.syntax.all._
import cats.{Eq, Show}

import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog
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

  /** Node states where consensus rounds must NOT start (recovery / download in progress). */
  private val roundBlockedStates: Set[NodeState] = Set(NodeState.WaitingForDownload, NodeState.DownloadInProgress)

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
          case IgnoreUnexpectedRumor(r) =>
            log.warn(ConsensusLog.format(ConsensusLog.Rumor, "n/a", "n/a", "event" -> "UNEXPECTED_RUMOR", "type" -> r.getClass.getSimpleName))

          case _ if running => handleWhileBusy(cmd)
          case _            => handleWhileIdle(cmd)
        }
      }

  private def handleWhileIdle(cmd: ConsensusCommand): F[Unit] =
    cmd match {
      case StartRound(trigger) => startRound(trigger)
      case TimeTick            => startRound(Some(TimeTrigger))
      case FacilitateByEvent   => startRound(Some(EventTrigger))
      case RoundCompleted      => log.warn(ConsensusLog.format(ConsensusLog.Lifecycle, "n/a", "n/a", "event" -> "IDLE_ROUND_COMPLETED"))
      case ConsensusFinished(_, _, _) =>
        log.warn(ConsensusLog.format(ConsensusLog.Lifecycle, "n/a", "n/a", "event" -> "IDLE_CONSENSUS_FINISHED"))
      case InitializeFromDownload(key, art, c) =>
        transitions.initFromDownload(key.asInstanceOf[Key], art.asInstanceOf[Signed[Artifact]], c.asInstanceOf[Ctx])
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
        completeRound(log.debug(ConsensusLog.format(ConsensusLog.Lifecycle, "n/a", "n/a", "event" -> "ROUND_COMPLETED_NO_OUTCOME")))
      case ConsensusFinished(key, _, trigger) =>
        completeRound(
          log.info(ConsensusLog.format(ConsensusLog.Lifecycle, key.toString, "n/a", "event" -> "CONSENSUS_FINISHED")) >>
            roundRunner.afterConsensusFinish(trigger)
        )
      case WithdrawFromConsensus => pending.setEvent()
      case _                     => Async[F].unit
    }

  private def startRound(trigger: Option[ConsensusTrigger]): F[Unit] =
    isRunning.get.ifM(
      ifTrue = log.debug(ConsensusLog.format(ConsensusLog.Lifecycle, "n/a", "n/a", "event" -> "START_ROUND_SKIPPED", "reason" -> "already_running")),
      ifFalse = nodeStorage.getNodeState.flatMap { state =>
        if (!roundBlockedStates.contains(state))
          log.info(
            ConsensusLog.format(
              ConsensusLog.Lifecycle,
              "n/a",
              "n/a",
              "event" -> "FSM_ROUND_START",
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
            ConsensusLog.Lifecycle,
            "n/a",
            "n/a",
            "event" -> "ROUND_BLOCKED_BY_STATE",
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
