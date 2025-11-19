package io.constellationnetwork.node.shared.infrastructure.consensus

import cats._
import cats.data.Ior.{Both, Right}
import cats.effect._
import cats.effect.std.{Random, Supervisor}
import cats.kernel.Next
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.message.GetConsensusOutcomeRequest
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.node.NodeState._
import io.constellationnetwork.schema.peer.Peer
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import monocle.Lens
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry.RetryDetails
import retry.RetryPolicies.{constantDelay, fullJitter, limitRetries}
import retry.syntax.all._

trait ConsensusManager[F[_], Key, Artifact, Context, Status, OutcomeC, Kind] {
  def registerForConsensus(observationKey: Key): F[Unit]

  def startFacilitatingAfterDownload(observationKey: Key, lastArtifact: Signed[Artifact], lastContext: Context): F[Unit]

  def startFacilitatingAfterRollback(lastKey: Key, initialOutcome: OutcomeC): F[Unit]

  def withdrawFromConsensus: F[Unit]

  private[consensus] def facilitateOnEvent: F[Unit]

  private[consensus] def processFacilitation(trigger: Option[ConsensusTrigger]): F[Unit]
  private[consensus] def processStateUpdate(key: Key): F[Unit]
}

object ConsensusManager {

  def make[F[_]: Async: Metrics: Random, Event, Key: Show: Order: Next, Artifact: Eq, Context: Eq, Status: Eq, OutcomeC, Kind](
    config: ConsensusConfig,
    consensusStorage: ConsensusStorage[F, Event, Key, Artifact, Context, Status, OutcomeC, Kind],
    consensusStateCreator: ConsensusStateCreator[F, Key, Artifact, Context, Status, OutcomeC, Kind],
    consensusStateUpdater: ConsensusStateUpdater[F, Key, Artifact, Context, Status, OutcomeC, Kind],
    consensusStateAdvancer: ConsensusStateAdvancer[F, Key, Artifact, Context, Status, OutcomeC, Kind],
    consensusStateRemover: ConsensusStateRemover[F, Key, Event, Artifact, Context, Status, OutcomeC, Kind],
    consensusOps: ConsensusOps[Status, Kind],
    nodeStorage: NodeStorage[F],
    clusterStorage: ClusterStorage[F],
    consensusClient: ConsensusClient[F, Key, OutcomeC]
  )(
    implicit S: Supervisor[F],
    _artifact: Lens[OutcomeC, Signed[Artifact]],
    _context: Lens[OutcomeC, Context],
    _key: Lens[OutcomeC, Key],
    _trigger: Lens[OutcomeC, ConsensusTrigger]
  ): F[(ConsensusManager[F, Key, Artifact, Context, Status, OutcomeC, Kind], ConsensusQueue[F, Key])] = {
    val logger = Slf4jLogger.getLoggerFromClass[F](ConsensusManager.getClass)

    val collectRegistrationRetryPolicy = limitRetries(3).join(fullJitter(2.seconds))
    val observationRetryPolicy = limitRetries[F](10).join(constantDelay(3.seconds))

    def collectRegistration(peer: Peer): F[Unit] =
      for {
        registrationResponse <- consensusClient.getRegistration.run(peer)
        maybeResult <- registrationResponse.maybeKey.traverse(consensusStorage.registerPeer(peer.id, _))
        _ <- (registrationResponse.maybeKey, maybeResult).traverseN {
          case (key, result) =>
            if (result)
              logger.info(s"Peer ${peer.id.show} registered at ${key.show}")
            else
              logger.warn(s"Peer ${peer.id.show} cannot be registered at ${key.show}")
        }
      } yield ()

    for {
      stallDetectionRef <- Ref.of[F, Map[Key, Long]](Map.empty)
      managerRef <- Deferred[F, ConsensusManager[F, Key, Artifact, Context, Status, OutcomeC, Kind]]

      queue <- ConsensusQueue.make[F, Key](
        processFacilitation = trigger => managerRef.get.flatMap(_.processFacilitation(trigger)),
        processStateUpdate = key => managerRef.get.flatMap(_.processStateUpdate(key)),
        logger
      )

      manager <- Async[F].delay {
        new ConsensusManager[F, Key, Artifact, Context, Status, OutcomeC, Kind] {

          def registerForConsensus(observationKey: Key): F[Unit] =
            consensusStorage
              .trySetObservationKey(observationKey)
              .ifM(
                nodeStorage.tryModifyState(NodeState.WaitingForObserving, NodeState.Observing) >>
                  logger.info(s"Registered for consensus {registrationKey=${observationKey.next.show}}"),
                new Throwable(
                  s"Registration for consensus failed {registrationKey=${observationKey.next.show}}. Already registered."
                ).raiseError[F, Unit]
              )

          def startFacilitatingAfterDownload(
            key: Key,
            lastArtifact: Signed[Artifact],
            lastContext: Context
          ): F[Unit] =
            S.supervise {
              def fetchOutcomeFromRandomPeer: F[Option[OutcomeC]] =
                (selectRandomPeer >>= fetchConsensusOutcome)
                  .retryingOnFailuresAndAllErrors(
                    wasSuccessful = wasSuccessful,
                    policy = observationRetryPolicy,
                    onFailure = onFailure,
                    onError = onError
                  )

              def selectRandomPeer: F[Peer] =
                clusterStorage.getResponsivePeers
                  .map(_.filter(_.state === Ready))
                  .flatMap(Random[F].elementOf)

              def fetchConsensusOutcome(peer: Peer): F[Option[OutcomeC]] =
                consensusClient
                  .getSpecificConsensusOutcome(GetConsensusOutcomeRequest(key))
                  .run(peer)

              def wasSuccessful(maybeOutcome: Option[OutcomeC]): F[Boolean] =
                maybeOutcome.exists { outcome =>
                  _key.get(outcome) === key &&
                  _artifact.get(outcome) === lastArtifact &&
                  _context.get(outcome) === lastContext
                }.pure[F]

              def onFailure(maybeOutcome: Option[OutcomeC], retryDetails: RetryDetails): F[Unit] =
                maybeOutcome.map { outcome =>
                  val sameArtifact = _artifact.get(outcome) === lastArtifact
                  val sameContext = _context.get(outcome) === lastContext
                  logger.info(
                    s"Observed outcome {key=${key.show}, outcomeKey=${_key
                        .get(outcome)}, sameArtifact=${sameArtifact.show}, sameContext=${sameContext.show}, attempt=${retryDetails.retriesSoFar}}"
                  )
                }.getOrElse(logger.info(s"Outcome not observed {key=${key.show}, attempt=${retryDetails.retriesSoFar}}"))

              def onError(err: Throwable, retryDetails: RetryDetails): F[Unit] =
                logger.error(err)(s"Error when trying to observe consensus outcome {attempt=${retryDetails.retriesSoFar}}")

              for {
                maybeOutcome <- fetchOutcomeFromRandomPeer
                outcome <- maybeOutcome.liftTo[F](new Throwable(s"Outcome not observed, giving up {key=${key.show}}"))
                _ <- consensusStorage
                  .trySetInitialConsensusOutcome(outcome)
                  .ifM(
                    nodeStorage.tryModifyState(Observing, WaitingForReady) >>
                      queue.requestFacilitation(none),
                    new Throwable("Error initializing consensus storage").raiseError[F, Unit]
                  )
              } yield ()
            }.void

          def facilitateOnEvent: F[Unit] =
            queue.requestFacilitation(EventTrigger.some)

          def startFacilitatingAfterRollback(lastKey: Key, initialOutcome: OutcomeC): F[Unit] =
            consensusStorage
              .trySetInitialConsensusOutcome(initialOutcome)
              .ifM(
                consensusStorage.trySetObservationKey(lastKey) >>
                  scheduleFacility,
                new Throwable("Error initializing consensus storage").raiseError[F, Unit]
              )

          private def scheduleFacility: F[Unit] =
            Clock[F].monotonic.map(_ + config.timeTriggerInterval).flatMap { nextTimeValue =>
              consensusStorage.setTimeTrigger(nextTimeValue) >>
                S.supervise {
                  val condTriggerWithTime = for {
                    maybeTimeTrigger <- consensusStorage.getTimeTrigger
                    currentTime <- Clock[F].monotonic
                    _ <- Applicative[F]
                      .whenA(maybeTimeTrigger.exists(currentTime >= _))(
                        queue.requestFacilitation(TimeTrigger.some)
                      )
                  } yield ()

                  Temporal[F].sleep(config.timeTriggerInterval) >> condTriggerWithTime
                    .handleErrorWith(logger.error(_)("Error triggering consensus with time trigger"))
                }.void
            }

          def withdrawFromConsensus: F[Unit] =
            for {
              maybeLastOutcome <- consensusStorage.clearAndGetLastConsensusOutcome
              _ <- maybeLastOutcome.traverse { lastOutcome =>
                consensusStateRemover.withdrawFromConsensus(_key.get(lastOutcome).next)
              }
              _ <- consensusStorage.clearObservationKey
            } yield ()

          def processFacilitation(trigger: Option[ConsensusTrigger]): F[Unit] =
            consensusStorage.getLastConsensusOutcome.flatMap { maybeLastOutcome =>
              maybeLastOutcome.traverse { lastOutcome =>
                val nextKey = _key.get(lastOutcome).next

                consensusStorage
                  .getResources(nextKey)
                  .flatMap { resources =>
                    logger.debug(s"Facilitating consensus {key=${nextKey.show}, trigger=${trigger.show}}") >>
                      consensusStateCreator
                        .tryFacilitateConsensus(nextKey, lastOutcome, trigger, resources)
                        .flatMap {
                          case Some(state) =>
                            scheduleStallDetection(nextKey, state) >>
                              queue.requestStateUpdate(nextKey)
                          case None =>
                            logger.debug(s"Cannot facilitate consensus {key=${nextKey.show}}")
                        }
                  }
              }.void
            }

          def processStateUpdate(key: Key): F[Unit] =
            consensusStorage.getLastKey.flatMap {
              case Some(lastKey) if key < lastKey =>
                logger.debug(s"Ignoring update for completed round {key=${key.show}, lastKey=${lastKey.show}}") >>
                  queue.clearPendingUpdate(key)

              case _ =>
                consensusStorage.getResources(key).flatMap { resources =>
                  consensusStateUpdater.tryUpdateConsensus(key, resources).flatMap {
                    case Some((oldState, newState)) =>
                      cancelStallDetection(key) >>
                        consensusStateAdvancer
                          .getConsensusOutcome(newState)
                          .fold {
                            (oldState.status =!= newState.status)
                              .pure[F]
                              .ifM(
                                scheduleStallDetection(key, newState) >>
                                  queue.requestStateUpdate(key),
                                handleStatusUnchanged(key, newState.status)
                              )
                          } {
                            case (previousKey, newOutcome) =>
                              queue.clearPendingUpdate(key) >>
                                handleConsensusCompletion(key, previousKey, newOutcome)
                          }

                    case None =>
                      consensusStorage.getState(key).flatMap {
                        case Some(state) =>
                          handleStatusUnchanged(key, state.status)
                        case None =>
                          logger.debug(s"No state found {key=${key.show}}") >>
                            queue.clearPendingUpdate(key)
                      }
                  }
                }
            }

          private def handleStatusUnchanged(key: Key, status: Status): F[Unit] =
            if (isActiveCollectingPhase(status)) {
              Temporal[F].sleep(config.activePhaseRetryInterval) >>
                queue.requestStateUpdate(key)
            } else {
              queue.clearPendingUpdate(key)
            }

          private def isActiveCollectingPhase(status: Status): Boolean =
            consensusOps.isCollectingPhase(status)

          private def handleConsensusCompletion(
            key: Key,
            previousKey: Previous[Key],
            newOutcome: OutcomeC
          ): F[Unit] =
            for {
              finishedAt <- Clock[F].monotonic
              _ <- consensusStorage.getState(key).flatMap {
                _.traverse { state =>
                  val duration = finishedAt - state.createdAt
                  logger.info(s"Consensus completed {key=${key.show}, duration=${duration.toMillis}ms}") >>
                    Metrics[F].recordTime("dag_consensus_duration", duration)
                }
              }

              _ <- Temporal[F].sleep(config.roundCompletionDelay)

              _ <- consensusStorage
                .tryUpdateLastConsensusOutcomeWithCleanup(previousKey, newOutcome)
                .ifM(
                  afterConsensusFinish(_trigger.get(newOutcome)),
                  logger.warn(s"Failed to update last consensus outcome for {key=${key.show}}")
                )
              _ <- nodeStorage.tryModifyStateGetResult(WaitingForReady, Ready).void
            } yield ()

          private def afterConsensusFinish(majorityTrigger: ConsensusTrigger): F[Unit] =
            majorityTrigger match {
              case EventTrigger => afterEventTrigger
              case TimeTrigger  => afterTimeTrigger
            }

          private def afterEventTrigger: F[Unit] =
            for {
              maybeTimeTrigger <- consensusStorage.getTimeTrigger
              currentTime <- Clock[F].monotonic
              containsTriggerEvent <- consensusStorage.containsTriggerEvent
              _ <-
                if (maybeTimeTrigger.exists(currentTime >= _))
                  queue.requestFacilitation(TimeTrigger.some)
                else if (containsTriggerEvent)
                  queue.requestFacilitation(EventTrigger.some)
                else if (maybeTimeTrigger.isEmpty)
                  queue.requestFacilitation(none)
                else
                  Applicative[F].unit
            } yield ()

          private def afterTimeTrigger: F[Unit] =
            for {
              _ <- scheduleFacility
              containsTriggerEvent <- consensusStorage.containsTriggerEvent
              _ <-
                if (containsTriggerEvent) {
                  queue.requestFacilitation(EventTrigger.some)
                } else {
                  Applicative[F].unit
                }
            } yield ()

          private def scheduleStallDetection(key: Key, state: ConsensusState[Key, Status, OutcomeC, Kind]): F[Unit] =
            StallDetection.scheduleStallDetection(
              key,
              state,
              config,
              stallDetectionRef,
              consensusOps,
              consensusStorage,
              consensusStateUpdater,
              clusterStorage,
              queue,
              logger
            )

          private def cancelStallDetection(key: Key): F[Unit] =
            stallDetectionRef.update(_ - key)
        }
      }

      _ <- managerRef.complete(manager)
      _ <- S.supervise(
        nodeStorage.nodeStates
          .filter(_ === NodeState.Leaving)
          .evalTap(_ => manager.withdrawFromConsensus)
          .compile
          .drain
      ) >>
        S.supervise(
          clusterStorage.peerChanges.mapFilter {
            case Both(_, peer) if peer.state === NodeState.Observing => peer.some
            case Right(peer) if peer.state === NodeState.Observing   => peer.some
            case _                                                   => none[Peer]
          }
            .filter(_.isResponsive)
            .parEvalMapUnbounded { peer =>
              collectRegistration(peer)
                .retryingOnAllErrors(
                  collectRegistrationRetryPolicy,
                  (err, retryDetails) =>
                    logger.error(err)(
                      s"Error collecting consensus registration {peerId=${peer.id.show}, attempt=${retryDetails.retriesSoFar}}"
                    )
                )
                .handleErrorWith(err => logger.error(err)(s"Unable to collect registration from peer ${peer.show}"))
            }
            .compile
            .drain
        )
    } yield (manager, queue)
  }
}
