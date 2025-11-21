package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.effect.kernel.{Async, Temporal}
import cats.effect.std.Supervisor
import cats.kernel.{Eq, Next}
import cats.syntax.all._

import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusResources
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.schema.peer.PeerId

import eu.timepit.refined.auto._
import monocle.Lens

/** Handles consensus round facilitation and post-consensus trigger scheduling.
  *
  * ==Why This Class Exists==
  *
  * The FSM routes commands but doesn't contain business logic. RoundRunner handles:
  *   - Starting rounds (spreading our facility declaration)
  *   - Scheduling next rounds after consensus completes
  *   - Detecting stalled consensus (slow/dead peers)
  *
  * ==Round Lifecycle==
  *
  * {{{
  *   runRound(trigger)
  *     │
  *     ├── Get last outcome, compute next key
  *     │
  *     ├── facilitateRound()
  *     │     └── creator.tryFacilitateConsensus() → Spread Facility declaration
  *     │
  *     ├── startStallDetection() → Background fiber watches for stalls
  *     │
  *     └── doInitialCheck() → Handle single-node case
  *           │
  *           └── RETURNS (non-blocking)
  *
  *   // Progress happens via:
  *   // 1. Rumors arrive → RumorHandler stores → CheckUpdate queued
  *   // 2. StateTransitions.checkUpdate() advances state
  *   // 3. When finished → ConsensusFinished queued
  *   // 4. FSM calls afterConsensusFinish()
  * }}}
  *
  * ==Post-Consensus Triggers==
  *
  * After consensus completes, we schedule the next round:
  *   - After TimeTrigger: Schedule next time trigger, check for pending events
  *   - After EventTrigger: Check if time trigger is due, or more events pending
  *
  * ==Stall Detection==
  *
  * Prevents slow/dead peers from blocking consensus forever:
  * {{{
  *   1. sleep(declarationTimeout)     // Wait for declarations
  *   2. Check if status changed       // Exit if progressed
  *   3. tryLockAndSpreadAck()         // Lock, spread what we've seen
  *   4. sleep(lockDuration)           // Give peers one more chance
  *   5. markPeersAsWithdrawn()        // Mark missing as withdrawn
  *   6. triggerAdvancementCheck()     // Re-evaluate with fewer peers
  * }}}
  *
  * @see
  *   StateTransitions for state advancement logic
  * @see
  *   ConsensusFSM for how afterConsensusFinish is called
  */
class ConsensusRoundRunner[F[_]: Async: Metrics, Event, Key: Next, Artifact, Ctx, Status: Eq, Outcome, Kind](
  ctx: ConsensusEngineContext[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind]
)(implicit outcomeKey: Lens[Outcome, Key], supervisor: Supervisor[F]) {

  import ctx.{advancer, config, creator, logger, ops, queue, storage, updater}
  def runRound(trigger: Option[ConsensusTrigger]): F[Unit] =
    for {
      _ <- logger.info(s"Facilitating consensus round with trigger=$trigger")
      maybeOutcome <- storage.getLastConsensusOutcome

      _ <- maybeOutcome match {
        case None =>
          logger.warn("No previous outcome; cannot start round.") >>
            queue.offer(ConsensusCommand.RoundCompleted)

        case Some(outcome) =>
          val nextKey = outcomeKey.get(outcome).next
          facilitateRound(outcome, nextKey, trigger)
      }
    } yield ()

  private def facilitateRound(lastOutcome: Outcome, key: Key, trigger: Option[ConsensusTrigger]): F[Unit] =
    for {
      resources <- storage.getResources(key)
      facilitated <- creator.tryFacilitateConsensus(key, lastOutcome, trigger, resources)

      _ <- facilitated match {
        case Some(state) =>
          logger.info(s"Facilitated consensus at key=$key") >>
            startStallDetection(key, state) >>
            doInitialCheck(key)

        case None =>
          storage.getState(key).flatMap {
            case Some(existingState) =>
              logger.debug(s"State already exists for key=$key, checking progress") >>
                startStallDetection(key, existingState) >>
                doInitialCheck(key)

            case None =>
              logger.warn(s"Could not facilitate and no existing state for key=$key") >>
                queue.offer(ConsensusCommand.RoundCompleted)
          }
      }
    } yield ()

  private def doInitialCheck(key: Key): F[Unit] =
    for {
      resources <- storage.getResources(key)
      maybeUpdate <- updater.tryUpdateConsensus(key, resources)

      _ <- maybeUpdate match {
        case Some((_, newState)) =>
          advancer.getConsensusOutcome(newState) match {
            case Some(_) =>
              queue.offer(ConsensusCommand.CheckUpdate(key))

            case None =>
              logger.debug(s"Initial check: updated to ${newState.status}, waiting for declarations")
          }

        case None =>
          logger.debug(s"Initial check: no update for key=$key, waiting for declarations")
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
        if (maybeTimeTrigger.exists(currentTime >= _)) {
          queue.offer(ConsensusCommand.StartRound(Some(TimeTrigger)))
        } else if (containsTriggerEvent) {
          queue.offer(ConsensusCommand.StartRound(Some(EventTrigger)))
        } else if (maybeTimeTrigger.isEmpty) {
          scheduleTimeTrigger >> queue.offer(ConsensusCommand.StartRound(None))
        } else {
          Async[F].unit
        }
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
      _ <- supervisor.supervise {
        Temporal[F].sleep(config.timeTriggerInterval) >>
          checkAndTriggerTime.handleErrorWith(err => logger.error(err)("Error triggering consensus with time trigger"))
      }
    } yield ()

  private def checkAndTriggerTime: F[Unit] =
    for {
      maybeTimeTrigger <- storage.getTimeTrigger
      currentTime <- Async[F].monotonic
      _ <- queue
        .offer(ConsensusCommand.TimeTick)
        .whenA(maybeTimeTrigger.exists(currentTime >= _))
    } yield ()

  private def startStallDetection(key: Key, state: ConsensusState[Key, Status, Outcome, Kind]): F[Unit] =
    if (ops.isFinished(state.status)) {
      Async[F].unit
    } else {
      supervisor.supervise {
        stallDetectionPhases(key, state)
          .handleErrorWith(err => logger.error(err)(s"Error in stall detection for key=$key"))
      }.void
    }

  private def stallDetectionPhases(key: Key, originalState: ConsensusState[Key, Status, Outcome, Kind]): F[Unit] =
    for {
      _ <- Temporal[F].sleep(config.declarationTimeout)

      currentStateOpt <- storage.getState(key)

      _ <- currentStateOpt match {
        case Some(currentState) if ops.isFinished(currentState.status) =>
          Async[F].unit

        case Some(currentState) if currentState.status =!= originalState.status =>
          Async[F].unit

        case Some(_) =>
          for {
            lockResult <- tryLockAndSpreadAck(key, originalState)
            _ <- lockResult.traverse_ { _ =>
              Temporal[F].sleep(config.lockDuration) >>
                tryForceProgress(key, originalState.status)
            }
          } yield ()

        case None =>
          Async[F].unit
      }
    } yield ()

  private def tryLockAndSpreadAck(
    key: Key,
    state: ConsensusState[Key, Status, Outcome, Kind]
  ): F[Option[ConsensusState[Key, Status, Outcome, Kind]]] =
    updater.tryLockConsensus(key, state).flatMap {
      case Some((_, lockedState)) =>
        ops
          .maybeCollectingKind(lockedState.status)
          .traverse_ { ackKind =>
            storage.getResources(key).flatMap { resources =>
              updater.trySpreadAck(key, ackKind, resources)
            }
          }
          .as(lockedState.some)

      case None =>
        none.pure[F]
    }

  private def tryForceProgress(
    key: Key,
    originalStatus: Status
  ): F[Unit] =
    for {
      currentStateOpt <- storage.getState(key)

      _ <- currentStateOpt match {
        case Some(currentState)
            if currentState.status === originalStatus &&
              currentState.lockStatus === LockStatus.Closed =>
          forceProgressWithAvailablePeers(key, currentState)

        case _ =>
          Async[F].unit
      }
    } yield ()

  private def forceProgressWithAvailablePeers(
    key: Key,
    state: ConsensusState[Key, Status, Outcome, Kind]
  ): F[Unit] =
    for {
      resources <- storage.getResources(key)

      respondedPeers = getRespondedPeers(state.status, resources)
      allFacilitators = state.facilitators.value.toSet ++ state.withdrawnFacilitators.value
      missingPeers = allFacilitators -- respondedPeers -- state.withdrawnFacilitators.value

      _ <-
        if (missingPeers.nonEmpty) {
          logger.warn(s"Consensus stalled at key=$key. Missing: ${missingPeers.map(_.show).mkString(", ")}") >>
            Metrics[F].incrementCounter("dag_consensus_stall_forced_progress") >>
            markPeersAsWithdrawn(key, missingPeers, state.status) >>
            triggerAdvancementCheck(key)
        } else {
          logger.debug(s"All peers responded for key=$key, triggering re-check") >>
            triggerAdvancementCheck(key)
        }
    } yield ()

  private def getRespondedPeers(status: Status, resources: ConsensusResources[Artifact, Kind]): Set[PeerId] =
    ops
      .maybeCollectingKind(status)
      .map { kind =>
        val getter = ops.kindGetter(kind)
        resources.peerDeclarationsMap.collect {
          case (peerId, decls) if getter(decls).isDefined => peerId
        }.toSet
      }
      .getOrElse(Set.empty)

  private def markPeersAsWithdrawn(key: Key, peers: Set[PeerId], status: Status): F[Unit] =
    ops.maybeCollectingKind(status).traverse_ { kind =>
      peers.toList.traverse_ { peerId =>
        logger.info(s"Marking peer ${peerId.show} as withdrawn for key=$key") >>
          storage.addWithdrawPeerDeclaration(peerId, key, kind).void
      }
    }

  private def triggerAdvancementCheck(key: Key): F[Unit] =
    queue.offer(ConsensusCommand.CheckUpdate(key))
}
