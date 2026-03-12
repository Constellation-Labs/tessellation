package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.effect.Fiber
import cats.effect.kernel.{Outcome => _, _}
import cats.effect.std.Supervisor
import cats.kernel.Next
import cats.syntax.all._

import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics.unsafeLabelName

import eu.timepit.refined.auto._
import monocle.Lens

/** Handles consensus round facilitation and post-consensus scheduling.
  *
  * Stall detection is delegated to StallDetector for separation of concerns and testability.
  *
  * @see
  *   StallDetector for stall monitoring logic
  * @see
  *   StateTransitions for state advancement logic
  * @see
  *   ConsensusFSM for command routing
  */
class ConsensusRoundRunner[F[_]: Async: Metrics, Event, Key: Next, Artifact, Ctx, Status, Outcome, Kind](
  ctx: ConsensusEngineContext[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind],
  stallDetector: StallDetector[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind],
  roundFibersRef: Ref[F, List[Fiber[F, Throwable, Unit]]],
  cancelSignalRef: Ref[F, Option[Deferred[F, Unit]]]
)(implicit outcomeKey: Lens[Outcome, Key], supervisor: Supervisor[F]) {

  import ctx.{advancer, config, creator, logger, queue, storage, updater}

  /** Spawn a fiber tracked by the round lifecycle. Cancelled on round cleanup. */
  private def spawnTracked(task: F[Unit]): F[Unit] =
    supervisor.supervise(task).flatMap { fiber =>
      roundFibersRef.update(fiber :: _)
    }

  /** Cancel all fibers spawned during the current round. */
  private def cancelRoundFibers: F[Unit] =
    roundFibersRef.getAndSet(Nil).flatMap(_.traverse_(_.cancel))

  /** Clean up the current round: signal monitor to stop and cancel all tracked fibers. */
  def cleanupRound: F[Unit] =
    cancelSignalRef.getAndSet(None).flatMap(_.traverse_(_.complete(()).attempt.void)) >>
      cancelRoundFibers

  def runRound(trigger: Option[ConsensusTrigger]): F[Unit] =
    storage.getLastConsensusOutcome.flatMap {
      case None =>
        logger.warn("[CONSENSUS] No previous outcome; cannot start round.") >>
          Metrics[F].incrementCounter("dag_consensus_round_no_outcome") >>
          queue.offer(ConsensusCommand.RoundCompleted)

      case Some(outcome) =>
        val nextKey = outcomeKey.get(outcome).next
        val lastKey = outcomeKey.get(outcome)
        logger.info(
          s"[CONSENSUS] Facilitating round\n" +
            s"  key=$nextKey trigger=$trigger lastKey=$lastKey\n" +
            s"  declarationTimeout=${config.declarationTimeout} timeTriggerInterval=${config.timeTriggerInterval}"
        ) >>
          facilitateRound(outcome, nextKey, trigger)
    }

  private def facilitateRound(lastOutcome: Outcome, key: Key, trigger: Option[ConsensusTrigger]): F[Unit] =
    for {
      resources <- storage.getResources(key)
      facilitated <- creator.tryFacilitateConsensus(key, lastOutcome, trigger, resources)

      _ <- facilitated match {
        case Some(_) =>
          Metrics[F].incrementCounter(
            "dag_consensus_round_facilitated",
            Seq(unsafeLabelName("outcome") -> "success")
          ) >>
            logger.info(
              s"[CONSENSUS] Round facilitated key=$key, monitoring started " +
                s"maxStallCycles=${config.maxStallCycles} maxRoundDuration=${config.maxRoundDuration.map(d => s"${d.toSeconds}s").getOrElse("none")}"
            ) >>
            startRoundMonitor(key) >>
            doInitialCheck(key)

        case None =>
          handleExistingOrMissingState(key)
      }
    } yield ()

  private def handleExistingOrMissingState(key: Key): F[Unit] =
    storage.getState(key).flatMap {
      case Some(state) =>
        val statusName = state.status.getClass.getSimpleName.stripSuffix("$")
        Metrics[F].incrementCounter(
          "dag_consensus_round_facilitated",
          Seq(unsafeLabelName("outcome") -> "existing")
        ) >>
          logger.debug(s"[CONSENSUS] State already exists for key=$key status=$statusName, checking progress") >>
          startRoundMonitor(key) >>
          doInitialCheck(key)

      case None =>
        Metrics[F].incrementCounter(
          "dag_consensus_round_facilitated",
          Seq(unsafeLabelName("outcome") -> "no_state")
        ) >>
          logger.warn(s"[CONSENSUS] Could not facilitate and no existing state for key=$key") >>
          queue.offer(ConsensusCommand.RoundCompleted)
    }

  private def doInitialCheck(key: Key): F[Unit] =
    for {
      resources <- storage.getResources(key)
      maybeUpdate <- updater.tryUpdateConsensus(key, resources)

      _ <- maybeUpdate.traverse_ {
        case (_, newState) =>
          val statusName = newState.status.getClass.getSimpleName.stripSuffix("$")
          advancer.getConsensusOutcome(newState) match {
            case Some(_) => queue.offer(ConsensusCommand.CheckUpdate(key))
            case None =>
              logger.debug(
                s"[CONSENSUS] Initial check: key=$key status=$statusName " +
                  s"facilitators=${newState.facilitators.value.size} leader=${newState.leader.show.take(8)}... waiting for declarations"
              )
          }
      }
    } yield ()

  def afterConsensusFinish(majorityTrigger: ConsensusTrigger): F[Unit] =
    majorityTrigger match {
      case EventTrigger => afterEventTrigger
      case TimeTrigger  => afterTimeTrigger
    }

  private def afterEventTrigger: F[Unit] =
    for {
      maybeTimeTrigger <- storage.getTimeTrigger
      currentTime <- Async[F].monotonic
      containsTriggerEvent <- storage.containsTriggerEvent

      _ <-
        if (maybeTimeTrigger.exists(currentTime >= _))
          queue.offer(ConsensusCommand.StartRound(Some(TimeTrigger)))
        else if (maybeTimeTrigger.isEmpty)
          scheduleTimeTrigger >> queue.offer(ConsensusCommand.StartRound(None))
        else if (containsTriggerEvent)
          queue.offer(ConsensusCommand.StartRound(Some(EventTrigger)))
        else
          Async[F].unit
    } yield ()

  private def afterTimeTrigger: F[Unit] =
    for {
      _ <- scheduleTimeTrigger
      containsTriggerEvent <- storage.containsTriggerEvent
      _ <- queue.offer(ConsensusCommand.StartRound(Some(EventTrigger))).whenA(containsTriggerEvent)
    } yield ()

  private def scheduleTimeTrigger: F[Unit] =
    for {
      nextTime <- Async[F].monotonic.map(_ + config.timeTriggerInterval)
      _ <- storage.setTimeTrigger(nextTime)
      _ <- spawnTracked {
        Temporal[F].sleep(config.timeTriggerInterval) >>
          checkAndTriggerTime.handleErrorWith(err => logger.error(err)("Error triggering consensus with time trigger"))
      }
    } yield ()

  private def checkAndTriggerTime: F[Unit] =
    for {
      maybeTimeTrigger <- storage.getTimeTrigger
      currentTime <- Async[F].monotonic
      _ <- queue.offer(ConsensusCommand.TimeTick).whenA(maybeTimeTrigger.exists(currentTime >= _))
    } yield ()

  private def startRoundMonitor(key: Key): F[Unit] =
    for {
      signal <- Deferred[F, Unit]
      _ <- cancelSignalRef.set(Some(signal))
      _ <- spawnTracked {
        stallDetector
          .monitor(key, signal)
          .handleErrorWith(err => logger.error(err)(s"Error in round monitor for key=$key"))
      }
    } yield ()
}
