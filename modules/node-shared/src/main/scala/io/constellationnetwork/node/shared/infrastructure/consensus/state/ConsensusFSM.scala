package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.effect.kernel.Async
import cats.effect.std.Random
import cats.syntax.all._
import cats.{Eq, Show}

import io.constellationnetwork.node.shared.infrastructure.consensus.engine.ConsensusCommand._
import io.constellationnetwork.node.shared.infrastructure.consensus.engine._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger._
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics.unsafeLabelName
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

  import ctx.{isRoundRunning => isRunning, logger => log, pending}

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
          case IgnoreUnexpectedRumor(r) => log.warn(s"Ignoring unexpected rumor: $r")

          case _ if running => handleWhileBusy(cmd)
          case _            => handleWhileIdle(cmd)
        }
      }

  private def handleWhileIdle(cmd: ConsensusCommand): F[Unit] =
    cmd match {
      case StartRound(trigger)        => startRound(trigger)
      case TimeTick                   => startRound(Some(TimeTrigger))
      case FacilitateByEvent          => startRound(Some(EventTrigger))
      case RoundCompleted             => log.warn("Received RoundCompleted while idle; ignoring.")
      case ConsensusFinished(_, _, _) => log.warn("Received ConsensusFinished while idle; ignoring.")
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
      case RoundCompleted => completeRound(log.debug("Round completed without outcome"))
      case ConsensusFinished(key, _, trigger) =>
        completeRound(log.info(s"Consensus finished at key=$key") >> roundRunner.afterConsensusFinish(trigger))
      case WithdrawFromConsensus => pending.setEvent()
      case _                     => Async[F].unit
    }

  private def startRound(trigger: Option[ConsensusTrigger]): F[Unit] =
    isRunning.get.ifM(
      ifTrue = log.debug(s"Ignoring StartRound($trigger) — round already running"),
      ifFalse = log.info(s"Starting consensus round with trigger=$trigger") >>
        Metrics[F].incrementCounter(
          "dag_consensus_fsm_round_started",
          Seq(unsafeLabelName("trigger_type") -> trigger.map(_.toString).getOrElse("none"))
        ) >>
        Metrics[F].updateGauge("dag_consensus_fsm_round_running", 1) >>
        isRunning.set(true) >>
        roundRunner.runRound(trigger)
    )

  private def completeRound(preAction: F[Unit]): F[Unit] =
    for {
      // Clean up BEFORE post-consensus scheduling: afterConsensusFinish spawns a timer
      // fiber via spawnTracked for the NEXT round. If cleanup runs after, it cancels that
      // fiber and no TimeTick ever fires — deadlocking consensus when running solo.
      _ <- roundRunner.cleanupRound
      _ <- preAction
      _ <- Metrics[F].incrementCounter("dag_consensus_fsm_round_completed")
      _ <- Metrics[F].updateGauge("dag_consensus_fsm_round_running", 0)
      _ <- isRunning.set(false)
      next <- pending.pullNext
      _ <- next.traverse_ {
        case TriggerPriority.Time  => ctx.queue.offer(StartRound(Some(TimeTrigger)))
        case TriggerPriority.Event => ctx.queue.offer(StartRound(Some(EventTrigger)))
      }
    } yield ()
}
