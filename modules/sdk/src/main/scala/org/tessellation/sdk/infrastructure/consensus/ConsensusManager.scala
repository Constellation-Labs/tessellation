package org.tessellation.sdk.infrastructure.consensus

import cats.effect._
import cats.kernel.Next
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.show._
import cats.syntax.traverse._
import cats.{Applicative, Order, Show}

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag
import scala.reflect.runtime.universe.TypeTag

import org.tessellation.ext.cats.syntax.next._
import org.tessellation.sdk.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}

import org.typelevel.log4cats.slf4j.Slf4jLogger

trait ConsensusManager[F[_], Key, Artifact] {

  def scheduleTriggerOnTime: F[Unit]
  def triggerOnStart: F[Unit]
  private[consensus] def triggerOnEvent: F[Unit]
  private[consensus] def checkForStateUpdate(key: Key)(resources: ConsensusResources[Artifact]): F[Unit]
  private[sdk] def checkForStateUpdateSync(key: Key)(resources: ConsensusResources[Artifact]): F[Unit]

}

object ConsensusManager {

  def make[F[_]: Async: Clock, Event, Key: Show: Order: Next: TypeTag: ClassTag, Artifact <: AnyRef: Show: TypeTag](
    timeTriggerInterval: FiniteDuration,
    consensusStorage: ConsensusStorage[F, Event, Key, Artifact],
    consensusStateUpdater: ConsensusStateUpdater[F, Key, Artifact]
  ): ConsensusManager[F, Key, Artifact] = new ConsensusManager[F, Key, Artifact] {

    private val logger = Slf4jLogger.getLoggerFromClass[F](ConsensusManager.getClass)

    def triggerOnEvent: F[Unit] =
      Spawn[F].start {
        internalTriggerWith(EventTrigger.some)
          .handleErrorWith(logger.error(_)(s"Error triggering consensus with event trigger"))
      }.void

    def triggerOnStart: F[Unit] =
      Spawn[F].start {
        internalTriggerWith(none)
          .handleErrorWith(logger.error(_)("Error triggering consensus with empty trigger"))
      }.void

    def scheduleTriggerOnTime: F[Unit] =
      Clock[F].monotonic.map(_ + timeTriggerInterval).flatMap { nextTimeValue =>
        consensusStorage.setTimeTrigger(nextTimeValue) >>
          Spawn[F].start {
            val condTriggerWithTime = for {
              maybeTimeTrigger <- consensusStorage.getTimeTrigger
              currentTime <- Clock[F].monotonic
              _ <- Applicative[F]
                .whenA(maybeTimeTrigger.exists(currentTime >= _))(internalTriggerWith(TimeTrigger.some))
            } yield ()

            Temporal[F].sleep(timeTriggerInterval) >> condTriggerWithTime
              .handleErrorWith(logger.error(_)(s"Error triggering consensus with time trigger"))
          }.void
      }

    def checkForStateUpdate(key: Key)(resources: ConsensusResources[Artifact]): F[Unit] =
      Spawn[F].start {
        internalCheckForStateUpdate(key, resources)
          .handleErrorWith(logger.error(_)(s"Error checking for consensus state update {key=${key.show}}"))
      }.void

    def checkForStateUpdateSync(key: Key)(resources: ConsensusResources[Artifact]): F[Unit] =
      internalCheckForStateUpdate(key, resources)

    private def internalTriggerWith(
      trigger: Option[ConsensusTrigger]
    ): F[Unit] =
      consensusStorage.getLastKeyAndArtifact.flatMap { maybeLastKeyAndArtifact =>
        maybeLastKeyAndArtifact.traverse { lastKeyAndArtifact =>
          val nextKey = lastKeyAndArtifact._1.next

          consensusStorage
            .getResources(nextKey)
            .flatMap { resources =>
              logger.debug(s"Trying to facilitate consensus {key=${nextKey.show}, trigger=${trigger.show}}") >>
                consensusStateUpdater.tryFacilitateConsensus(nextKey, trigger, resources, lastKeyAndArtifact).flatMap {
                  case Some(_) =>
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
        case Some(state) =>
          state.status match {
            case Finished(signedArtifact, majorityTrigger) =>
              val keyAndArtifact = state.key -> signedArtifact
              consensusStorage
                .tryUpdateLastKeyAndArtifactWithCleanup(state.lastKeyAndArtifact, keyAndArtifact)
                .ifM(
                  afterConsensusFinish(majorityTrigger),
                  logger.info("Skip triggering another consensus")
                )
            case _ =>
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
            internalTriggerWith(TimeTrigger.some)
          else if (containsTriggerEvent)
            internalTriggerWith(EventTrigger.some)
          else if (maybeTimeTrigger.isEmpty)
            internalTriggerWith(none) // when there's no time trigger scheduled yet, trigger again with nothing
          else
            Applicative[F].unit
      } yield ()

    private def afterTimeTrigger: F[Unit] =
      scheduleTriggerOnTime >> consensusStorage.containsTriggerEvent
        .ifM(internalTriggerWith(EventTrigger.some), Applicative[F].unit)
  }

}
