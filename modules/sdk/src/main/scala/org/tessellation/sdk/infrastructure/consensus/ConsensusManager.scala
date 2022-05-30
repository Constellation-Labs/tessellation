package org.tessellation.sdk.infrastructure.consensus

import cats.effect.{Async, Spawn}
import cats.kernel.Next
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._
import cats.syntax.traverse._
import cats.{Applicative, Order, Show}

import scala.reflect.ClassTag
import scala.reflect.runtime.universe.TypeTag

import org.tessellation.ext.cats.syntax.next._
import org.tessellation.sdk.domain.consensus.ConsensusFunctions
import org.tessellation.security.signature.Signed

import org.typelevel.log4cats.slf4j.Slf4jLogger

trait ConsensusManager[F[_], Event, Key, Artifact] {

  def checkForTrigger(event: Event): F[Unit]
  def checkForStateUpdate(key: Key)(resources: ConsensusResources[Artifact]): F[Unit]
  def checkForStateUpdateSync(key: Key)(resources: ConsensusResources[Artifact]): F[Unit]

}

object ConsensusManager {

  def make[F[_]: Async, Event, Key: Show: Order: Next: TypeTag: ClassTag, Artifact <: AnyRef: Show: TypeTag](
    consensusFns: ConsensusFunctions[F, Event, Key, Artifact],
    consensusStorage: ConsensusStorage[F, Event, Key, Artifact],
    consensusStateUpdater: ConsensusStateUpdater[F, Key, Artifact]
  ): ConsensusManager[F, Event, Key, Artifact] = new ConsensusManager[F, Event, Key, Artifact] {

    private val logger = Slf4jLogger.getLoggerFromClass[F](ConsensusManager.getClass)

    def checkForTrigger(event: Event): F[Unit] =
      Spawn[F].start {
        consensusStorage.getLastKeyAndArtifact
          .flatMap(_.traverse(internalCheckForTrigger(_, event)).void)
          .handleErrorWith(e => logger.error(e)("Error triggering consensus"))
      }.void

    def checkForStateUpdate(key: Key)(resources: ConsensusResources[Artifact]): F[Unit] =
      Spawn[F].start {
        internalCheckForStateUpdate(key, resources)
          .handleErrorWith(e => logger.error(e)("Error checking for consensus state update"))
      }.void

    def checkForStateUpdateSync(key: Key)(resources: ConsensusResources[Artifact]): F[Unit] =
      internalCheckForStateUpdate(key, resources)

    private def internalCheckForTrigger(lastKeyAndArtifact: (Key, Signed[Artifact]), event: Event): F[Unit] =
      if (consensusFns.triggerPredicate(lastKeyAndArtifact, event)) {
        val nextKey = lastKeyAndArtifact._1.next

        consensusStorage
          .getResources(nextKey)
          .flatMap { resources =>
            logger.debug(s"Triggering consensus for key ${nextKey.show}") >>
              consensusStateUpdater.tryFacilitateConsensus(nextKey, resources, lastKeyAndArtifact).flatMap {
                case Some(_) =>
                  internalCheckForStateUpdate(nextKey, resources)
                case None => Applicative[F].unit
              }
          }
      } else {
        Applicative[F].unit
      }

    private def internalCheckForStateUpdate(
      key: Key,
      resources: ConsensusResources[Artifact]
    ): F[Unit] =
      consensusStateUpdater.tryUpdateConsensus(key, resources).flatMap {
        case Some(state) =>
          state.status match {
            case Finished(signedArtifact) =>
              val keyAndArtifact = state.key -> signedArtifact
              consensusStorage
                .tryUpdateLastKeyAndArtifactWithCleanup(state.lastKeyAndArtifact, keyAndArtifact)
                .ifM(
                  checkAllEventsForTrigger(keyAndArtifact),
                  logger.info("Skip triggering another consensus")
                )
            case _ =>
              internalCheckForStateUpdate(key, resources)
          }
        case None => Applicative[F].unit
      }

    private def checkAllEventsForTrigger(lastKeyAndArtifact: (Key, Signed[Artifact])): F[Unit] =
      for {
        maybeEvent <- consensusStorage.findEvent { consensusFns.triggerPredicate(lastKeyAndArtifact, _) }
        _ <- maybeEvent.traverse(internalCheckForTrigger(lastKeyAndArtifact, _))
      } yield ()
  }

}
