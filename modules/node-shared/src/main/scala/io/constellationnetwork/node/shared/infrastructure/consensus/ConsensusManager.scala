package io.constellationnetwork.node.shared.infrastructure.consensus

import cats._
import cats.data.Ior.{Both, Right}
import cats.effect.std.{Random, Supervisor}
import cats.effect.{Async, Clock, Temporal}
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
import io.constellationnetwork.schema.peer.Peer.toP2PContext
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import monocle.Lens
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry.RetryDetails
import retry.RetryPolicies.{constantDelay, fullJitter, limitRetries}
import retry.syntax.all._

trait ConsensusManager[F[_], Key, Artifact, Context, Status, Outcome, Kind] {
  def registerForConsensus(observationKey: Key): F[Unit]

  def startFacilitatingAfterDownload(observationKey: Key, lastArtifact: Signed[Artifact], lastContext: Context): F[Unit]

  def startFacilitatingAfterRollback(lastKey: Key, initialOutcome: Outcome): F[Unit]

  def withdrawFromConsensus: F[Unit]

  private[consensus] def facilitateOnEvent: F[Unit]

  private[consensus] def checkForStateUpdate(key: Key)(resources: ConsensusResources[Artifact, Kind]): F[Unit]
}

object ConsensusManager {

  def make[F[_]: Async: Metrics: Random, Event, Key: Show: Order: Next, Artifact: Eq, Context: Eq, Status: Eq, Outcome, Kind](
    config: ConsensusConfig,
    consensusStorage: ConsensusStorage[F, Event, Key, Artifact, Context, Status, Outcome, Kind],
    consensusStateCreator: ConsensusStateCreator[F, Key, Artifact, Context, Status, Outcome, Kind],
    consensusStateUpdater: ConsensusStateUpdater[F, Key, Artifact, Context, Status, Outcome, Kind],
    consensusStateAdvancer: ConsensusStateAdvancer[F, Key, Artifact, Context, Status, Outcome, Kind],
    consensusStateRemover: ConsensusStateRemover[F, Key, Event, Artifact, Context, Status, Outcome, Kind],
    consensusOps: ConsensusOps[Status, Kind],
    nodeStorage: NodeStorage[F],
    clusterStorage: ClusterStorage[F],
    consensusClient: ConsensusClient[F, Key, Outcome]
  )(
    implicit S: Supervisor[F],
    _artifact: Lens[Outcome, Signed[Artifact]],
    _context: Lens[Outcome, Context],
    _key: Lens[Outcome, Key],
    _trigger: Lens[Outcome, ConsensusTrigger]
  ): F[ConsensusManager[F, Key, Artifact, Context, Status, Outcome, Kind]] = {
    val logger = Slf4jLogger.getLoggerFromClass[F](ConsensusManager.getClass)

    val collectRegistrationRetryPolicy = limitRetries(3).join(fullJitter(2.seconds))
    val observationRetryPolicy = limitRetries[F](10).join(constantDelay(3.seconds))

    def collectRegistration(peer: Peer): F[Unit] =
      for {
        registrationResponse <- Metrics[F].timedMetric(consensusClient.getRegistration.run(peer), "dag_consensus_collect_registration")
        maybeResult <- registrationResponse.maybeKey.traverse(consensusStorage.registerPeer(peer.id, _))
        _ <- (registrationResponse.maybeKey, maybeResult).traverseN {
          case (key, result) =>
            if (result)
              logger.info(s"Peer ${peer.id.show} registered at ${key.show}") >>
                Metrics[F].incrementCounter("dag_consensus_peer_registered")
            else {
              logger.warn(s"Peer ${peer.id.show} cannot be registered at ${key.show}") >>
                Metrics[F].incrementCounter("dag_consensus_peer_cannot_register")
            }
        }
      } yield ()

    val manager = new ConsensusManager[F, Key, Artifact, Context, Status, Outcome, Kind] {

      def registerForConsensus(observationKey: Key): F[Unit] = {
        Metrics[F].incrementCounter("dag_consensus_register_self")
        consensusStorage
          .trySetObservationKey(observationKey)
          .ifM(
            nodeStorage.tryModifyState(NodeState.WaitingForObserving, NodeState.Observing) >>
              logger.info(s"Registered for consensus {registrationKey=${observationKey.next.show}"),
            Metrics[F].incrementCounter("dag_consensus_register_self_failed") >>
              new Throwable(
                s"Registration for consensus failed {registrationKey=${observationKey.next.show}. Already registered at different key."
              ).raiseError[F, Unit]
          )
      }

      def startFacilitatingAfterDownload(key: Key, lastArtifact: Signed[Artifact], lastContext: Context): F[Unit] =
        S.supervise {
          def fetchOutcomeFromRandomPeer: F[Option[Outcome]] =
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

          def fetchConsensusOutcome(peer: Peer): F[Option[Outcome]] =
            consensusClient
              .getSpecificConsensusOutcome(GetConsensusOutcomeRequest(key))
              .run(peer)

          def wasSuccessful(maybeOutcome: Option[Outcome]): F[Boolean] =
            maybeOutcome.exists { outcome =>
              _key.get(outcome) === key &&
              _artifact.get(outcome) === lastArtifact &&
              _context.get(outcome) === lastContext
            }.pure[F]

          def onFailure(maybeOutcome: Option[Outcome], retryDetails: RetryDetails): F[Unit] =
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
            outcome <- maybeOutcome.liftTo[F](new Throwable(s"Outcome not observed, giving up {key=${key.show}"))
            _ <- consensusStorage
              .trySetInitialConsensusOutcome(outcome)
              .ifM(
                nodeStorage.tryModifyState(Observing, WaitingForReady) >>
                  internalFacilitateWith(none),
                new Throwable("Error initializing consensus storage").raiseError[F, Unit]
              )
          } yield ()
        }.void

      def facilitateOnEvent: F[Unit] =
        S.supervise {
          internalFacilitateWith(EventTrigger.some)
            .handleErrorWith(logger.error(_)(s"Error facilitating consensus with event trigger"))
        }.void

      def startFacilitatingAfterRollback(lastKey: Key, initialOutcome: Outcome): F[Unit] =
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
                  .whenA(maybeTimeTrigger.exists(currentTime >= _))(internalFacilitateWith(TimeTrigger.some))
              } yield ()

              Temporal[F].sleep(config.timeTriggerInterval) >> condTriggerWithTime
                .handleErrorWith(logger.error(_)(s"Error triggering consensus with time trigger"))
            }.void
        }

      def withdrawFromConsensus: F[Unit] =
        for {
          maybeLastOutcome <- consensusStorage.clearAndGetLastConsensusOutcome
          _ <- maybeLastOutcome.traverse { lastOutcome =>
            // Record consensus cancelation
            Metrics[F].incrementCounter("dag_consensus_withdrawal") >>
              consensusStateRemover.withdrawFromConsensus(_key.get(lastOutcome).next)
          }
          _ <- consensusStorage.clearObservationKey
        } yield ()

      def checkForStateUpdate(key: Key)(resources: ConsensusResources[Artifact, Kind]): F[Unit] =
        S.supervise {
          internalCheckForStateUpdate(key, resources)
            .handleErrorWith(logger.error(_)(s"Error checking for consensus state update {key=${key.show}}"))
        }.void

      private def internalFacilitateWith(
        trigger: Option[ConsensusTrigger]
      ): F[Unit] =
        consensusStorage.getLastConsensusOutcome.flatMap { maybeLastOutcome =>
          maybeLastOutcome.traverse { lastOutcome =>
            val nextKey = _key.get(lastOutcome).next

            consensusStorage
              .getResources(nextKey)
              .flatMap { resources =>
                logger.debug(s"Trying to facilitate consensus {key=${nextKey.show}, trigger=${trigger.show}}") >>
                  consensusStateCreator.tryFacilitateConsensus(nextKey, lastOutcome, trigger, resources).flatMap {
                    case Some(state) =>
                      stallDetection(nextKey, state) >>
                        internalCheckForStateUpdate(nextKey, resources)
                    case None => Applicative[F].unit
                  }
              }
          }.void
        }

      private def internalCheckForStateUpdate(
        key: Key,
        resources: ConsensusResources[Artifact, Kind]
      ): F[Unit] =
        consensusStateUpdater.tryUpdateConsensus(key, resources).flatMap {
          case Some((oldState, newState)) =>
            consensusStateAdvancer.getConsensusOutcome(newState) match {
              case Some((previousKey, newOutcome)) =>
                Metrics[F].recordDistribution("dag_consensus_manager_any_state_change", oldState.timeDeltaSeconds()) >>
                  Clock[F].monotonic.flatMap { finishedAt =>
                    Metrics[F].recordTime("dag_consensus_duration", finishedAt - newState.createdAt)
                  } >>
                  consensusStorage
                    .tryUpdateLastConsensusOutcomeWithCleanup(previousKey, newOutcome)
                    .ifM(
                      afterConsensusFinish(_trigger.get(newOutcome)),
                      logger.info("Skip triggering another consensus")
                    ) >>
                  nodeStorage.tryModifyStateGetResult(WaitingForReady, Ready).void
              case None =>
                stallDetection(key, newState).whenA(oldState.status =!= newState.status) >>
                  internalCheckForStateUpdate(key, resources)
            }
          case None => Applicative[F].unit
        }

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
              internalFacilitateWith(TimeTrigger.some)
            else if (containsTriggerEvent)
              internalFacilitateWith(EventTrigger.some)
            else if (maybeTimeTrigger.isEmpty)
              internalFacilitateWith(none) // when there's no time trigger scheduled yet, trigger again with nothing
            else
              Applicative[F].unit
        } yield ()

      private def afterTimeTrigger: F[Unit] =
        scheduleFacility >> consensusStorage.containsTriggerEvent
          .ifM(internalFacilitateWith(EventTrigger.some), Applicative[F].unit)

      private def stallDetection(key: Key, state: ConsensusState[Key, Status, Outcome, Kind]): F[Unit] =
        S.supervise {
          Temporal[F].sleep(config.declarationTimeout) >>
            Metrics[F].incrementCounter("dag_consensus_stall_detection_awoke") >>
            consensusStateUpdater.tryLockConsensus(key, state).flatMap { maybeResult =>
              if (maybeResult.isEmpty) {
                Metrics[F].incrementCounter("dag_consensus_stall_detection_lock_empty")
              } else {
                Metrics[F].incrementCounter("dag_consensus_stall_detection_lock_found")
              } >>
                maybeResult.traverse {
                  case (_, lockedState) =>
                    Temporal[F].sleep(config.lockDuration) >>
                      Metrics[F].updateGauge("dag_consensus_stall_detection_lock_state_updated_delta", lockedState.timeDeltaSeconds()) >>
                      consensusOps.maybeCollectingKind(lockedState.status).traverse { ackKind =>
                        Metrics[F].incrementCounter("dag_consensus_stall_detection_ack_kind") >>
                          consensusStorage.getResources(key).flatMap { resources =>
                            Metrics[F].incrementCounter("dag_consensus_stall_detection_try_spread_ack") >>
                              consensusStateUpdater.trySpreadAck(key, ackKind, resources)
                          }
                      }
                }.void
            }
        }.void

    }

    S.supervise(
      nodeStorage.nodeStates
        .filter(_ === NodeState.Leaving)
        .evalTap { _ =>
          Metrics[F].incrementCounter("dag_consensus_manager_withdraw_from") >>
            manager.withdrawFromConsensus
        }
        .compile
        .drain
    ) >>
      S.supervise(
        clusterStorage.peerChanges.mapFilter {
          case Both(_, peer) if peer.state === NodeState.Observing =>
            peer.some
          case Right(peer) if peer.state === NodeState.Observing =>
            peer.some
          case _ =>
            none[Peer]
        }
          .filter(_.isResponsive)
          .parEvalMapUnbounded { peer =>
            collectRegistration(peer)
              .retryingOnAllErrors(
                collectRegistrationRetryPolicy,
                (err, retryDetails) =>
                  logger
                    .error(err)(s"Error collecting consensus registration {peerId=${peer.id.show}, attempt=${retryDetails.retriesSoFar}}")
              )
              .handleErrorWith(err => logger.error(err)(s"Unable to collect registration from peer ${peer.show}"))
          }
          .compile
          .drain
      ).as(manager)
  }
}
