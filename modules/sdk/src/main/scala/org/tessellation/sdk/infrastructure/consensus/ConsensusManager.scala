package org.tessellation.sdk.infrastructure.consensus

import cats._
import cats.data.Ior.{Both, Right}
import cats.effect._
import cats.effect.std.Supervisor
import cats.kernel.Next
import cats.syntax.all._

import scala.reflect.runtime.universe.TypeTag

import org.tessellation.ext.cats.syntax.next._
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.node.NodeState.{Observing, Ready}
import org.tessellation.schema.peer.Peer.toP2PContext
import org.tessellation.schema.peer.{Peer, PeerId}
import org.tessellation.schema.security.hash.Hash
import org.tessellation.schema.security.signature.Signed
import org.tessellation.sdk.config.types.ConsensusConfig
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.node.NodeStorage
import org.tessellation.sdk.infrastructure.consensus.registration.Deregistration
import org.tessellation.sdk.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import org.tessellation.sdk.infrastructure.metrics.Metrics

import eu.timepit.refined.auto._
import io.circe.{Decoder, Encoder}
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait ConsensusManager[F[_], Key, Artifact] {

  def startObservingAfter(lastKey: Key, peer: Peer): F[Unit]
  def startFacilitatingAfter(lastKey: Key, lastArtifact: Signed[Artifact]): F[Unit]
  private[consensus] def facilitateOnEvent: F[Unit]
  private[consensus] def checkForStateUpdate(key: Key)(resources: ConsensusResources[Artifact]): F[Unit]

}

object ConsensusManager {

  def make[F[_]: Async: Clock: Metrics, Event, Key: Show: Order: Next: TypeTag: Encoder: Decoder, Artifact: Eq: Show: TypeTag](
    config: ConsensusConfig,
    consensusStorage: ConsensusStorage[F, Event, Key, Artifact],
    consensusStateCreator: ConsensusStateCreator[F, Key, Artifact],
    consensusStateUpdater: ConsensusStateUpdater[F, Key, Artifact],
    nodeStorage: NodeStorage[F],
    clusterStorage: ClusterStorage[F],
    consensusClient: ConsensusClient[F, Key],
    gossip: Gossip[F]
  )(implicit S: Supervisor[F]): F[ConsensusManager[F, Key, Artifact]] = {
    val logger = Slf4jLogger.getLoggerFromClass[F](ConsensusManager.getClass)

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

    val manager = new ConsensusManager[F, Key, Artifact] {

      def startObservingAfter(lastKey: Key, peer: Peer): F[Unit] =
        S.supervise {
          val observationKey = lastKey.next
          val facilitationKey = lastKey.nextN(2L)

          consensusStorage.setOwnRegistration(facilitationKey) >>
            consensusStorage.setLastKey(lastKey) >>
            consensusStorage
              .getResources(observationKey)
              .flatMap { resources =>
                logger.debug(s"Trying to observe consensus {key=${observationKey.show}}") >>
                  consensusStateCreator.tryObserveConsensus(observationKey, lastKey, resources, peer.id).flatMap {
                    case Some(state) =>
                      stallDetection(observationKey, state) >>
                        internalCheckForStateUpdate(observationKey, resources)
                    case None => Applicative[F].unit
                  }
              }
              .handleErrorWith(logger.error(_)(s"Error observing consensus {key=${observationKey.show}}"))
        }.void

      def facilitateOnEvent: F[Unit] =
        S.supervise {
          internalFacilitateWith(EventTrigger.some)
            .handleErrorWith(logger.error(_)(s"Error facilitating consensus with event trigger"))
        }.void

      def startFacilitatingAfter(lastKey: Key, lastArtifact: Signed[Artifact]): F[Unit] =
        consensusStorage.setLastKeyAndStatus(lastKey, Finished(lastArtifact, EventTrigger, Hash.empty)) >>
          consensusStorage.setOwnRegistration(lastKey.next) >>
          scheduleFacility

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

      def checkForStateUpdate(key: Key)(resources: ConsensusResources[Artifact]): F[Unit] =
        S.supervise {
          internalCheckForStateUpdate(key, resources)
            .handleErrorWith(logger.error(_)(s"Error checking for consensus state update {key=${key.show}}"))
        }.void

      private def internalFacilitateWith(
        trigger: Option[ConsensusTrigger]
      ): F[Unit] =
        consensusStorage.getLastKeyAndStatus.flatMap { maybeLastKeyAndStatus =>
          maybeLastKeyAndStatus.traverse {
            case (lastKey, Some(lastStatus)) =>
              val nextKey = lastKey.next

              consensusStorage
                .getResources(nextKey)
                .flatMap { resources =>
                  logger.debug(s"Trying to facilitate consensus {key=${nextKey.show}, trigger=${trigger.show}}") >>
                    consensusStateCreator.tryFacilitateConsensus(nextKey, lastKey, lastStatus, trigger, resources).flatMap {
                      case Some(state) =>
                        stallDetection(nextKey, state) >>
                          internalCheckForStateUpdate(nextKey, resources)
                      case None => Applicative[F].unit
                    }
                }
            case _ => Applicative[F].unit
          }.void
        }

      private def internalCheckForStateUpdate(
        key: Key,
        resources: ConsensusResources[Artifact]
      ): F[Unit] =
        consensusStateUpdater.tryUpdateConsensus(key, resources).flatMap {
          case Some((oldState, newState)) =>
            newState.status match {
              case finish @ Finished(_, majorityTrigger, _) =>
                Clock[F].monotonic.flatMap { finishedAt =>
                  Metrics[F].recordTime("dag_consensus_duration", finishedAt - newState.createdAt)
                } >>
                  deregisterPeers(key, newState.removedFacilitators) >>
                  consensusStorage
                    .tryUpdateLastKeyAndStatusWithCleanup(newState.lastKey, key, finish)
                    .ifM(
                      afterConsensusFinish(majorityTrigger),
                      logger.info("Skip triggering another consensus")
                    ) >>
                  nodeStorage.tryModifyStateGetResult(Observing, Ready).void
              case _ =>
                stallDetection(key, newState).whenA(oldState.status =!= newState.status) >>
                  internalCheckForStateUpdate(key, resources)
            }
          case None => Applicative[F].unit
        }

      private def deregisterPeers(key: Key, peers: Set[PeerId]): F[Unit] =
        peers.toList.traverse { peerId =>
          consensusStorage.deregisterPeer(peerId, key)
        }.void

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

      private def stallDetection(key: Key, state: ConsensusState[Key, Artifact]): F[Unit] =
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
        .filter(NodeState.leaving.contains)
        .evalTap { _ =>
          (consensusStorage.getLastKey.map(_.map(_.next)), consensusStorage.getOwnRegistration).tupled.flatMap {
            _.mapN(Order[Key].max)
              .map(Deregistration(_))
              .traverse(gossip.spread[Deregistration[Key]])
          }
        }
        .compile
        .drain
    ) >>
      S.supervise(
        clusterStorage.peerChanges.mapFilter {
          case Both(_, peer) if peer.state === NodeState.Observing =>
            peer.some
          case Right(peer) if NodeState.inConsensus.contains(peer.state) =>
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
