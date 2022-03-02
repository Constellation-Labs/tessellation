package org.tessellation.sdk.infrastructure.consensus

import cats.effect.Async
import cats.kernel.Next
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._
import cats.syntax.traverse._
import cats.{Applicative, Order, Show}

import scala.reflect.ClassTag
import scala.reflect.runtime.universe.TypeTag

import org.tessellation.ext.cats.syntax.next._
import org.tessellation.sdk.domain.consensus.ConsensusFunctions

import org.typelevel.log4cats.slf4j.Slf4jLogger

trait ConsensusManager[F[_], Event, Key, Artifact] {

  def checkForTrigger(event: Event): F[Unit]
  def checkForStateUpdate(key: Key)(resources: ConsensusResources[Artifact]): F[Unit]
}

object ConsensusManager {

  def make[F[_]: Async, Event, Key: Show: Order: Next: TypeTag: ClassTag, Artifact <: AnyRef: Show: TypeTag](
    consensusFns: ConsensusFunctions[F, Event, Key, Artifact],
    consensusStorage: ConsensusStorage[F, Event, Key, Artifact],
    consensusStateUpdater: ConsensusStateUpdater[F, Key, Artifact]
  ): ConsensusManager[F, Event, Key, Artifact] =
    new ConsensusManager[F, Event, Key, Artifact] {

      private val logger = Slf4jLogger.getLoggerFromClass[F](ConsensusManager.getClass)

      def checkForTrigger(event: Event): F[Unit] =
        consensusStorage.getLastKeyAndArtifact.flatMap(_.traverse(internalCheckForTrigger(_, event)).void)

      def checkForStateUpdate(key: Key)(resources: ConsensusResources[Artifact]): F[Unit] =
        consensusStorage.getState(key).flatMap(_.traverse(internalCheckForStateUpdate(key, _, resources)).void)

      private def internalCheckForTrigger(lastKeyAndArtifact: (Key, Artifact), event: Event): F[Unit] =
        if (consensusFns.triggerPredicate(lastKeyAndArtifact, event)) {
          val nextKey = lastKeyAndArtifact._1.next
          consensusStorage
            .getState(nextKey)
            .map(_.isEmpty)
            .ifM(
              logger.debug(s"Triggering consensus for key ${nextKey.show}") >>
                consensusStateUpdater.tryFacilitateConsensus(nextKey, lastKeyAndArtifact).flatMap {
                  case Some(state) =>
                    consensusStorage
                      .getResources(nextKey)
                      .flatMap(_.traverse(internalCheckForStateUpdate(nextKey, state, _)))
                      .void
                  case None => Applicative[F].unit
                },
              Applicative[F].unit
            )
        } else
          Applicative[F].unit

      private def internalCheckForStateUpdate(
        key: Key,
        state: ConsensusState[Key, Artifact],
        resources: ConsensusResources[Artifact]
      ): F[Unit] =
        if (isStateUpdateRequired(state, resources))
          consensusStateUpdater.tryAdvanceConsensus(key, resources).flatMap {
            case Some(state) =>
              state.status match {
                case MajoritySigned(signedArtifact) =>
                  val keyAndArtifact = state.key -> signedArtifact.value
                  consensusStorage
                    .tryUpdateLastKeyAndArtifactWithCleanup(state.lastKeyAndArtifact, keyAndArtifact)
                    .ifM(
                      checkAllForTrigger(keyAndArtifact),
                      logger.info("Skip triggering another consensus")
                    )
                case _ =>
                  internalCheckForStateUpdate(key, state, resources)
              }
            case None => Applicative[F].unit
          } else
          Applicative[F].unit

      private def checkAllForTrigger(lastKeyAndArtifact: (Key, Artifact)): F[Unit] =
        for {
          maybeEvent <- consensusStorage.findEvent { consensusFns.triggerPredicate(lastKeyAndArtifact, _) }
          _ <- maybeEvent.traverse(internalCheckForTrigger(lastKeyAndArtifact, _))
        } yield ()

      private def isStateUpdateRequired(
        state: ConsensusState[Key, Artifact],
        resources: ConsensusResources[Artifact]
      ): Boolean = {
        def allDeclarations[A](getter: PeerDeclaration => Option[A]): Boolean =
          state.facilitators.traverse(resources.peerDeclarations.get).flatMap(_.traverse(getter)).isDefined
        state.status match {
          case _: Facilitated[Artifact]      => allDeclarations(_.upperBound)
          case _: ProposalMade[Artifact]     => allDeclarations(_.proposal)
          case _: MajoritySelected[Artifact] => allDeclarations(_.signature)
          case _: MajoritySigned[Artifact]   => false /** terminal state */
        }
      }
    }
}
