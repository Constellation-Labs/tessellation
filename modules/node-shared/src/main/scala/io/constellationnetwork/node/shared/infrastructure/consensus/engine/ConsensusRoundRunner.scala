package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.effect.Fiber
import cats.effect.kernel.{Outcome => _, _}
import cats.effect.std.Supervisor
import cats.kernel.Next
import cats.syntax.all._

import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.{Category, Event => LogEvent}
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics.unsafeLabelName
import io.constellationnetwork.security.signature.Signed

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
)(
  implicit outcomeKey: Lens[Outcome, Key],
  outcomeArtifact: Lens[Outcome, Signed[Artifact]],
  supervisor: Supervisor[F]
) {

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
        // No outcome exists yet — round cannot run. Bind the release to the current local epoch;
        // even a startup completion must not cancel a recovery attempt created before it drains.
        ConsensusLog.warn(logger, Category.Lifecycle, "n/a", "n/a", LogEvent.NoPreviousOutcome) >>
          Metrics[F].incrementCounter("dag_consensus_round_no_outcome") >>
          storage.getRoundAttemptId.flatMap(id => queue.offer(ConsensusCommand.RoundCompleted(id)))

      case Some(outcome) =>
        val nextKey = outcomeKey.get(outcome).next
        val lastKey = outcomeKey.get(outcome)
        val lastArtifact = outcomeArtifact.get(outcome)
        val lastSignerIds = lastArtifact.proofs.toList.map(p => ConsensusLog.pid(p.id.toPeerId)).sorted.mkString(",")
        ConsensusLog.debug(
          logger,
          Category.Lifecycle,
          nextKey.toString,
          "n/a",
          LogEvent.RoundFacilitating,
          "trigger" -> trigger.toString,
          "lastKey" -> lastKey.toString,
          "lastSigners" -> lastSignerIds,
          "lastSignerCount" -> lastArtifact.proofs.size.toString,
          "declarationTimeout" -> config.declarationTimeout.toString,
          "timeTriggerInterval" -> config.timeTriggerInterval.toString
        ) >>
          facilitateRound(outcome, nextKey, trigger)
    }

  private def facilitateRound(lastOutcome: Outcome, key: Key, trigger: Option[ConsensusTrigger]): F[Unit] =
    for {
      resources <- storage.getResources(key)
      // Retry/view-change history is passed as a compatibility and diagnostic signal only. Consensus
      // implementations must not treat a local counter or single VCV as quorum evidence for
      // proposal-critical view/leader seeding.
      priorAbandonmentCount = (resources.viewChangeVotes.keys.map(_._2).maxOption match {
        case Some(maxToView) => maxToView + 1
        case None            => 0L
      }).toInt
      facilitated <- creator.tryFacilitateConsensus(key, lastOutcome, trigger, resources, priorAbandonmentCount)

      // Validators must not produce solo — solo production from multiple validators creates
      // divergent forks when they restart simultaneously. Abort the round if this is a
      // validator node and the facilitator set is just self.
      // Note: only block when selfId IS in the facilitator set. If selfId is NOT a facilitator
      // (e.g., a joining node observing rounds before candidate registration), let it follow
      // the round as an observer - it won't produce its own snapshot.
      isValidator <- ctx.nodeStorage.isValidatorMode
      _ <- facilitated match {
        case Some(_) =>
          storage.getState(key).flatMap { maybeState =>
            val leaderInfo = maybeState.map(s => ConsensusLog.pid(s.leader)).getOrElse("unknown")
            val count = maybeState.map(_.facilitators.value.size).getOrElse(0)
            val role = maybeState.map(s => ConsensusLog.role(ctx.selfId, s.leader)).getOrElse("n/a")
            val selfIsFacilitator = maybeState.exists(_.facilitators.value.contains(ctx.selfId))

            if (isValidator && count <= 1 && selfIsFacilitator) {
              // Validator refused to facilitate solo: snapshot current attemptId so the FSM drops
              // this RoundCompleted if another node's gossip advances our round before dequeue.
              ConsensusLog.warn(
                logger,
                Category.Lifecycle,
                key.toString,
                role,
                LogEvent.RoundBlockedByState,
                "reason" -> "validator_solo_blocked",
                "facilitators" -> count.toString
              ) >>
                Metrics[F].incrementCounter("dag_consensus_validator_solo_blocked") >>
                storage.getRoundAttemptId.flatMap(id => queue.offer(ConsensusCommand.RoundCompleted(id)))
            } else {
              Metrics[F].incrementCounter(
                "dag_consensus_round_facilitated",
                Seq(unsafeLabelName("outcome") -> "success")
              ) >>
                ConsensusLog.info(
                  logger,
                  Category.Lifecycle,
                  key.toString,
                  role,
                  LogEvent.RoundFacilitated,
                  "leader" -> leaderInfo,
                  "facilitators" -> count.toString
                ) >>
                startRoundMonitor(key) >>
                doInitialCheck(key)
            }
          }

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
          ConsensusLog.debug(
            logger,
            Category.Lifecycle,
            key.toString,
            ConsensusLog.role(ctx.selfId, state.leader),
            LogEvent.StateExists,
            "status" -> statusName
          ) >>
          startRoundMonitor(key) >>
          doInitialCheck(key)

      case None =>
        // No state for this key — state may have been wiped mid-facilitate, or the creator never
        // produced one. Snapshot attemptId; a concurrent advance should override this completion.
        Metrics[F].incrementCounter(
          "dag_consensus_round_facilitated",
          Seq(unsafeLabelName("outcome") -> "no_state")
        ) >>
          ConsensusLog.warn(logger, Category.Lifecycle, key.toString, "n/a", LogEvent.NoState) >>
          storage.getRoundAttemptId.flatMap(id => queue.offer(ConsensusCommand.RoundCompleted(id)))
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
              ConsensusLog.debug(
                logger,
                Category.Lifecycle,
                key.toString,
                ConsensusLog.role(ctx.selfId, newState.leader),
                LogEvent.InitialCheck,
                "phase" -> statusName,
                "facilitators" -> newState.facilitators.value.size.toString,
                "leader" -> ConsensusLog.pid(newState.leader)
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

      _ <-
        if (maybeTimeTrigger.exists(currentTime >= _))
          queue.offer(ConsensusCommand.StartRound(Some(TimeTrigger)))
        else if (maybeTimeTrigger.isEmpty)
          scheduleTimeTrigger >> queue.offer(ConsensusCommand.StartRound(None))
        else
          // No timer expired, timer is scheduled — the supervised timer fiber
          // will fire TimeTick when the interval elapses. New events will offer
          // FacilitateByEvent directly via GlobalSnapshotEventsPublisherDaemon.
          Async[F].unit
    } yield ()

  private def afterTimeTrigger: F[Unit] =
    scheduleTimeTrigger

  /** Schedule the next time-triggered round.
    *
    * CRITICAL: Uses `supervisor.supervise` directly instead of `spawnTracked` because the timer fiber must survive round cleanup.
    * `spawnTracked` adds fibers to `roundFibersRef`, which `cleanupRound` cancels at the start of the NEXT round's completion. If the timer
    * fires between rounds (e.g., an EventTrigger round runs before the timer expires), `cleanupRound` would cancel the timer fiber, leaving
    * `storage.timeTrigger` set but no fiber alive to fire `TimeTick` — permanently deadlocking consensus.
    */
  private def scheduleTimeTrigger: F[Unit] =
    for {
      nextTime <- Async[F].monotonic.map(_ + config.timeTriggerInterval)
      _ <- storage.setTimeTrigger(nextTime)
      _ <- supervisor.supervise {
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

  /** Re-arm monitoring for a round that still exists after a queued abandonment was suppressed at drain time. The original monitor
    * intentionally terminates after it enqueues `AbandonRound`; without this re-arm, the safety checks that preserve a newer attempt would
    * also leave that attempt permanently unmonitored.
    */
  def ensureRoundMonitor(key: Key): F[Unit] =
    storage.getState(key).flatMap {
      case Some(state) if advancer.getConsensusOutcome(state).isEmpty => startRoundMonitor(key)
      case _                                                          => Async[F].unit
    }

  private def startRoundMonitor(key: Key): F[Unit] =
    for {
      signal <- Deferred[F, Unit]
      // Replace, rather than merely overwrite, any prior monitor signal. This keeps
      // ensureRoundMonitor idempotent when a newer transition already re-armed the round.
      previousSignal <- cancelSignalRef.getAndSet(Some(signal))
      _ <- previousSignal.traverse_(_.complete(()).attempt.void)
      _ <- spawnTracked {
        stallDetector
          .monitor(key, signal)
          .handleErrorWith(err => logger.error(err)(s"Error in round monitor for key=$key"))
      }
    } yield ()
}
