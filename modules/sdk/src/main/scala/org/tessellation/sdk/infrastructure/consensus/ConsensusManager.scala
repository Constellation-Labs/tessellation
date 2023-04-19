package org.tessellation.sdk.infrastructure.consensus

import cats._
import cats.data.Ior.{Both, Right}
import cats.effect._
import cats.effect.std.{Random, Supervisor}
import cats.kernel.Next
import cats.syntax.all._

import scala.concurrent.duration._

import org.tessellation.ext.cats.syntax.next._
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.node.NodeState._
import org.tessellation.schema.peer.Peer.toP2PContext
import org.tessellation.schema.peer.{Peer, PeerId}
import org.tessellation.sdk.config.types.ConsensusConfig
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.consensus.ConsensusManager
import org.tessellation.sdk.domain.node.NodeStorage
import org.tessellation.sdk.infrastructure.consensus.message.GetConsensusOutcomeRequest
import org.tessellation.sdk.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto._
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry.RetryPolicies.{constantDelay, limitRetries}
import retry.syntax.all._

object ConsensusManager {

  def make[F[_]: Async: Random: Metrics, Event, Key: Show: Order: Next, Artifact: Eq, Context: Eq](
    config: ConsensusConfig,
    consensusStorage: ConsensusStorage[F, Event, Key, Artifact, Context],
    consensusStateCreator: ConsensusStateCreator[F, Key, Artifact, Context],
    consensusStateUpdater: ConsensusStateUpdater[F, Key, Artifact, Context],
    consensusStateRemover: ConsensusStateRemover[F, Key, Artifact, Context],
    nodeStorage: NodeStorage[F],
    clusterStorage: ClusterStorage[F],
    consensusClient: ConsensusClient[F, Key, Artifact, Context],
    selfId: PeerId
  )(implicit S: Supervisor[F]): F[ConsensusManager[F, Key, Artifact, Context]] = {
    val logger = Slf4jLogger.getLoggerFromClass[F](ConsensusManager.getClass)

    val observationRetryPolicy = limitRetries[F](5).join(constantDelay(30.seconds))

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

    val manager = new ConsensusManager[F, Key, Artifact, Context] {

      def startObserving: F[Unit] =
        S.supervise {

          def getObservedOutcome: F[ConsensusOutcome[Key, Artifact, Context]] = for {
            readyPeers <- clusterStorage.getResponsivePeers
              .map(_.filter(_.state === Ready))
            selectedPeer <- Random[F].elementOf(readyPeers)
            observedOutcome <- observePeer(selectedPeer)
          } yield observedOutcome

          getObservedOutcome
            .retryingOnAllErrors(
              observationRetryPolicy,
              (err, retryDetails) =>
                logger.error(err)(s"Error when trying to observe consensus outcome {attempt=${retryDetails.retriesSoFar}}") >>
                  nodeStorage.tryModifyStateGetResult(Observing, WaitingForObserving).void
            )
            .flatMap { observedOutcome =>
              consensusStorage
                .trySetInitialConsensusOutcome(observedOutcome)
                .ifM(
                  nodeStorage.tryModifyState(Observing, WaitingForReady) >>
                    internalFacilitateWith(none),
                  new Throwable("Error initializing consensus storage").raiseError[F, Unit]
                )
            }
            .handleErrorWith { err =>
              logger.error(err)("Error when trying to observe consensus outcome, giving up.")
            }
        }.void

      private def observePeer(peer: Peer): F[ConsensusOutcome[Key, Artifact, Context]] = {

        def getSpecificOutcome(key: Key): F[Option[ConsensusOutcome[Key, Artifact, Context]]] = consensusClient
          .getSpecificConsensusOutcome(GetConsensusOutcomeRequest(key))
          .map(_.filter(_.key === key))
          .run(peer)

        for {
          maybeLatestOutcome <- consensusClient.getLatestConsensusOutcome.run(peer)
          latestOutcome <- maybeLatestOutcome.liftTo[F](new Throwable(s"Peer ${peer.id.show} doesn't have last outcome"))
          observationKey = latestOutcome.key.nextN(config.observation.offset)
          _ <- logger.info(s"Awaiting for consensus outcome {key=${observationKey.show}, peerId=${peer.id.show}}")
          _ <- consensusStorage.setOwnRegistration(observationKey.next)
          _ <- nodeStorage.tryModifyState(NodeState.WaitingForObserving, NodeState.Observing)
          observationOutcome <- Temporal[F].timeout(
            (Temporal[F].sleep(config.observation.interval) >> getSpecificOutcome(observationKey)).untilDefinedM,
            config.observation.timeout
          )
        } yield observationOutcome
      }

      def facilitateOnEvent: F[Unit] =
        S.supervise {
          internalFacilitateWith(EventTrigger.some)
            .handleErrorWith(logger.error(_)(s"Error facilitating consensus with event trigger"))
        }.void

      def startFacilitatingAfter(lastKey: Key, lastArtifact: Signed[Artifact], lastContext: Context): F[Unit] = {
        val initialOutcome = ConsensusOutcome[Key, Artifact, Context](
          lastKey,
          List(selfId),
          Set.empty,
          Set.empty,
          Finished(lastArtifact, lastContext, EventTrigger, Set.empty, Hash.empty)
        )

        consensusStorage
          .trySetInitialConsensusOutcome(initialOutcome)
          .ifM(
            consensusStorage.setOwnRegistration(lastKey.next) >>
              scheduleFacility,
            new Throwable("Error initializing consensus storage").raiseError[F, Unit]
          )
      }

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
            consensusStateRemover.withdrawFromConsensus(lastOutcome.key.next)
          }
        } yield ()

      def checkForStateUpdate(key: Key)(resources: ConsensusResources[Artifact]): F[Unit] =
        S.supervise {
          internalCheckForStateUpdate(key, resources)
            .handleErrorWith(logger.error(_)(s"Error checking for consensus state update {key=${key.show}}"))
        }.void

      private def internalFacilitateWith(
        trigger: Option[ConsensusTrigger]
      ): F[Unit] =
        consensusStorage.getLastConsensusOutcome.flatMap { maybeLastOutcome =>
          maybeLastOutcome.traverse { lastOutcome =>
            val nextKey = lastOutcome.key.next

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
        resources: ConsensusResources[Artifact]
      ): F[Unit] =
        consensusStateUpdater.tryUpdateConsensus(key, resources).flatMap {
          case Some((oldState, newState)) =>
            newState.status match {
              case finish @ Finished(_, _, majorityTrigger, _, _) =>
                Clock[F].monotonic.flatMap { finishedAt =>
                  Metrics[F].recordTime("dag_consensus_duration", finishedAt - newState.createdAt)
                } >>
                  consensusStorage
                    .tryUpdateLastConsensusOutcomeWithCleanup(
                      newState.lastOutcome.key,
                      ConsensusOutcome(
                        newState.key,
                        newState.facilitators,
                        newState.removedFacilitators,
                        newState.withdrawnFacilitators,
                        finish
                      )
                    )
                    .ifM(
                      afterConsensusFinish(majorityTrigger),
                      logger.info("Skip triggering another consensus")
                    ) >>
                  nodeStorage.tryModifyStateGetResult(WaitingForReady, Ready).void
              case _ =>
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

      private def stallDetection(key: Key, state: ConsensusState[Key, Artifact, Context]): F[Unit] =
        S.supervise {
          Temporal[F].sleep(config.declarationTimeout) >>
            consensusStateUpdater.tryLockConsensus(key, state).flatMap { maybeResult =>
              maybeResult.traverse {
                case (_, lockedState) =>
                  Temporal[F].sleep(config.lockDuration) >>
                    lockedState.maybeCollectingKind.traverse { ackKind =>
                      consensusStorage.getResources(key).flatMap { resources =>
                        consensusStateUpdater.trySpreadAck(key, ackKind, resources)
                      }
                    }
              }
            }
        }.void

    }

    S.supervise(
      nodeStorage.nodeStates
        .filter(_ === NodeState.Leaving)
        .evalTap { _ =>
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
              .handleErrorWith(err => logger.error(err)(s"Error exchanging registration with peer ${peer.show}"))
          }
          .compile
          .drain
      ).as(manager)
  }
}
