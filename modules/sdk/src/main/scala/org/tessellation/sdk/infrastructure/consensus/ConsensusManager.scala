package org.tessellation.sdk.infrastructure.consensus

import cats.effect.{Async, Spawn}
import cats.kernel.Next
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.functorFilter._
import cats.syntax.option._
import cats.syntax.show._
import cats.syntax.traverse._
import cats.{Applicative, Order, Show}

import scala.reflect.ClassTag
import scala.reflect.runtime.universe.TypeTag

import org.tessellation.ext.cats.syntax.next._
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.consensus.ConsensusFunctions
import org.tessellation.security.signature.Signed

import org.typelevel.log4cats.slf4j.Slf4jLogger

trait ConsensusManager[F[_], Event, Key, Artifact] {

  def checkForTrigger(event: Event): F[Unit]
  def checkForStateUpdate(key: Key)(resources: ConsensusResources[Artifact]): F[Unit]
  def removeFacilitator(facilitator: PeerId): F[Unit]
}

object ConsensusManager {

  def make[F[_]: Async, Event, Key: Show: Order: Next: TypeTag: ClassTag, Artifact <: AnyRef: Show: TypeTag](
    clusterStorage: ClusterStorage[F],
    consensusFns: ConsensusFunctions[F, Event, Key, Artifact],
    consensusStorage: ConsensusStorage[F, Event, Key, Artifact],
    consensusStateUpdater: ConsensusStateUpdater[F, Key, Artifact]
  ): F[ConsensusManager[F, Event, Key, Artifact]] = {
    val manager = new ConsensusManager[F, Event, Key, Artifact] {

      private val logger = Slf4jLogger.getLoggerFromClass[F](ConsensusManager.getClass)

      def checkForTrigger(event: Event): F[Unit] =
        consensusStorage.getLastKeyAndArtifact
          .flatMap(
            _.traverse(internalCheckForTrigger(_, event)).void
          )

      def checkForStateUpdate(key: Key)(resources: ConsensusResources[Artifact]): F[Unit] =
        consensusStorage
          .getState(key)
          .flatMap(
            _.traverse(internalCheckForStateUpdate(key, _, resources)).void
          )

      def removeFacilitator(facilitator: PeerId): F[Unit] =
        consensusStorage.getStates
          .flatMap(
            _.map(_.key)
              .traverse(internalRemoveFacilitator(facilitator))
              .void
          )

      private def internalRemoveFacilitator(facilitator: PeerId)(key: Key) =
        for {
          maybeState <- consensusStateUpdater.tryRemoveFacilitator(key, facilitator)
          maybeResources <- consensusStorage.getResources(key)
          _ <- maybeState.flatTraverse { state =>
            maybeResources.traverse(internalCheckForStateUpdate(key, state, _))
          }
        } yield ()

      private def internalCheckForTrigger(lastKeyAndArtifact: (Key, Signed[Artifact]), event: Event): F[Unit] =
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
                case Finished(signedArtifact) =>
                  val keyAndArtifact = state.key -> signedArtifact
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
          } else Applicative[F].unit

      private def checkAllForTrigger(lastKeyAndArtifact: (Key, Signed[Artifact])): F[Unit] =
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
          case _: Facilitated[Artifact]    => allDeclarations(_.upperBound)
          case _: ProposalMade[Artifact]   => allDeclarations(_.proposal)
          case _: MajoritySigned[Artifact] => allDeclarations(_.signature)
          case _: Finished[Artifact]       => false /* terminal state */
        }
      }
    }

    Spawn[F].start {
      clusterStorage.peerChanges.mapFilter {
        case (id, None)                                                => id.some
        case (id, Some(peer)) if NodeState.absent.contains(peer.state) => id.some
        case _                                                         => none
      }.evalMap(manager.removeFacilitator).compile.drain
    }.as(manager)
  }
}
