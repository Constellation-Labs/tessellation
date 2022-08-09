package org.tessellation.sdk.infrastructure.consensus

import cats.effect.kernel.{Async, Ref}
import cats.effect.std.Semaphore
import cats.kernel.Next
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.contravariantSemigroupal._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.traverse._
import cats.syntax.traverseFilter._
import cats.{Eq, Order, Show}

import scala.concurrent.duration.FiniteDuration

import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.gossip.Ordinal
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.infrastructure.consensus.declaration.{Facility, MajoritySignature, Proposal}
import org.tessellation.security.signature.Signed

import io.chrisdavenport.mapref.MapRef
import monocle.syntax.all._

trait ConsensusStorage[F[_], Event, Key, Artifact] {

  private[consensus] trait ModifyStateFn[B]
      extends (Option[ConsensusState[Key, Artifact]] => F[Option[(Option[ConsensusState[Key, Artifact]], B)]])

  private[sdk] def getState(key: Key): F[Option[ConsensusState[Key, Artifact]]]

  private[sdk] def getStates: F[List[ConsensusState[Key, Artifact]]]

  private[consensus] def condModifyState[B](key: Key)(modifyStateFn: ModifyStateFn[B]): F[Option[B]]

  private[consensus] def containsTriggerEvent: F[Boolean]

  private[consensus] def addTriggerEvent(peerId: PeerId, peerEvent: (Ordinal, Event)): F[Unit]

  private[consensus] def addEvent(peerId: PeerId, peerEvent: (Ordinal, Event)): F[Unit]

  private[consensus] def addEvents(events: Map[PeerId, List[(Ordinal, Event)]]): F[Unit]

  private[consensus] def pullEvents(upperBound: Bound): F[Map[PeerId, List[(Ordinal, Event)]]]

  private[consensus] def getUpperBound: F[Bound]

  private[consensus] def getResources(key: Key): F[ConsensusResources[Artifact]]

  private[consensus] def getTimeTrigger: F[Option[FiniteDuration]]

  private[consensus] def setTimeTrigger(time: FiniteDuration): F[Unit]

  private[consensus] def clearTimeTrigger: F[Unit]

  private[sdk] def getPeerDeclarations(key: Key): F[Map[PeerId, PeerDeclarations]]

  private[sdk] def addRemovedFacilitator(key: Key, facilitator: PeerId): F[ConsensusResources[Artifact]]

  private[consensus] def addArtifact(key: Key, artifact: Artifact): F[ConsensusResources[Artifact]]

  private[consensus] def addFacility(peerId: PeerId, key: Key, facility: Facility): F[ConsensusResources[Artifact]]

  private[consensus] def addProposal(peerId: PeerId, key: Key, proposal: Proposal): F[ConsensusResources[Artifact]]

  private[consensus] def addSignature(
    peerId: PeerId,
    key: Key,
    signature: MajoritySignature
  ): F[ConsensusResources[Artifact]]

  private[consensus] def setLastKeyAndArtifact(key: Key, artifact: Signed[Artifact]): F[Unit]

  private[consensus] def setLastKey(key: Key): F[Unit]

  private[consensus] def getLastKeyAndArtifact: F[Option[(Key, Option[Signed[Artifact]])]]

  private[consensus] def tryUpdateLastKeyAndArtifactWithCleanup(
    oldKey: Key,
    newKey: Key,
    newArtifact: Signed[Artifact]
  ): F[Boolean]

}

object ConsensusStorage {

  def make[F[_]: Async: KryoSerializer, Event, Key: Show: Next: Eq, Artifact <: AnyRef: Show: Eq](
    lastKeyAndArtifact: Option[(Key, Option[Signed[Artifact]])]
  ): F[ConsensusStorage[F, Event, Key, Artifact]] =
    for {
      stateUpdateSemaphore <- Semaphore[F](1)
      lastKeyAndArtifactR <- Ref.of(lastKeyAndArtifact)
      timeTriggerR <- Ref.of(none[FiniteDuration])
      eventsR <- MapRef.ofConcurrentHashMap[F, PeerId, PeerEvents[Event]]()
      statesR <- MapRef.ofConcurrentHashMap[F, Key, ConsensusState[Key, Artifact]]()
      resourcesR <- MapRef.ofConcurrentHashMap[F, Key, ConsensusResources[Artifact]]()
    } yield make(stateUpdateSemaphore, lastKeyAndArtifactR, timeTriggerR, eventsR, statesR, resourcesR)

  def make[F[_]: Async: KryoSerializer, Event, Key: Show: Next: Eq, Artifact <: AnyRef: Show: Eq](
    stateUpdateSemaphore: Semaphore[F],
    lastKeyAndArtifactR: Ref[F, Option[(Key, Option[Signed[Artifact]])]],
    timeTriggerR: Ref[F, Option[FiniteDuration]],
    eventsR: MapRef[F, PeerId, Option[PeerEvents[Event]]],
    statesR: MapRef[F, Key, Option[ConsensusState[Key, Artifact]]],
    resourcesR: MapRef[F, Key, Option[ConsensusResources[Artifact]]]
  ): ConsensusStorage[F, Event, Key, Artifact] =
    new ConsensusStorage[F, Event, Key, Artifact] {

      def getState(key: Key): F[Option[ConsensusState[Key, Artifact]]] =
        statesR(key).get

      def getStates: F[List[ConsensusState[Key, Artifact]]] =
        statesR.keys.flatMap(_.traverseFilter(key => statesR(key).get))

      def getResources(key: Key): F[ConsensusResources[Artifact]] =
        resourcesR(key).get.map(_.getOrElse(ConsensusResources.empty))

      def getTimeTrigger: F[Option[FiniteDuration]] =
        timeTriggerR.get

      def setTimeTrigger(time: FiniteDuration): F[Unit] =
        timeTriggerR.set(time.some)

      def clearTimeTrigger: F[Unit] =
        timeTriggerR.set(none)

      def getPeerDeclarations(key: Key): F[Map[PeerId, PeerDeclarations]] =
        resourcesR(key).get.map(_.map(_.peerDeclarationsMap).getOrElse(Map.empty))

      def condModifyState[B](key: Key)(modifyStateFn: ModifyStateFn[B]): F[Option[B]] =
        stateUpdateSemaphore.permit.use { _ =>
          for {
            (maybeState, setter) <- statesR(key).access
            maybeResult <- modifyStateFn(maybeState)

            maybeB <- maybeResult.traverse {
              case (maybeState, b) =>
                setter(maybeState)
                  .ifM(
                    b.pure[F],
                    new Throwable(
                      "Failed consensus state update, all consensus state updates should be sequenced with a semaphore"
                    ).raiseError[F, B]
                  )
            }
          } yield maybeB
        }

      def setLastKeyAndArtifact(key: Key, artifact: Signed[Artifact]): F[Unit] =
        lastKeyAndArtifactR.set((key, artifact.some).some)

      def setLastKey(key: Key): F[Unit] =
        lastKeyAndArtifactR.set((key, none).some)

      def getLastKeyAndArtifact: F[Option[(Key, Option[Signed[Artifact]])]] = lastKeyAndArtifactR.get

      private[consensus] def tryUpdateLastKeyAndArtifactWithCleanup(
        lastKey: Key,
        newKey: Key,
        newArtifact: Signed[Artifact]
      ): F[Boolean] =
        lastKeyAndArtifactR.modify {
          case Some((actualLastKey, _)) if actualLastKey === lastKey =>
            ((newKey, newArtifact.some).some, true)
          case other @ _ =>
            (other, false)
        }.flatTap(_ => cleanupStateAndResource(lastKey))

      private def cleanupStateAndResource(key: Key): F[Unit] =
        condModifyState[Unit](key) { _ =>
          (none[ConsensusState[Key, Artifact]], ()).some.pure[F]
        }.void

      def containsTriggerEvent: F[Boolean] =
        eventsR.keys.flatMap { keys =>
          keys.existsM { peerId =>
            eventsR(peerId).get
              .map(_.flatMap(_.trigger).isDefined)
          }
        }

      def addTriggerEvent(peerId: PeerId, peerEvent: (Ordinal, Event)): F[Unit] =
        addEvents(peerId, List(peerEvent), updateTrigger = true)

      def addEvent(peerId: PeerId, peerEvent: (Ordinal, Event)): F[Unit] =
        addEvents(peerId, List(peerEvent), updateTrigger = false)

      def addEvents(events: Map[PeerId, List[(Ordinal, Event)]]): F[Unit] =
        events.toList.traverse {
          case (peerId, peerEvents) =>
            addEvents(peerId, peerEvents, updateTrigger = false)
        }.void

      private def addEvents(peerId: PeerId, events: List[(Ordinal, Event)], updateTrigger: Boolean) =
        eventsR(peerId).update { maybePeerEvents =>
          maybePeerEvents
            .getOrElse(PeerEvents.empty[Event])
            .focus(_.events)
            .modify(events ++ _)
            .focus(_.trigger)
            .modify { maybeCurrentTrigger =>
              if (updateTrigger) {
                val maybeNewTrigger = events.map(_._1).maximumOption

                (maybeCurrentTrigger, maybeNewTrigger)
                  .mapN(Order[Ordinal].max)
                  .orElse(maybeCurrentTrigger)
                  .orElse(maybeNewTrigger)
              } else
                maybeCurrentTrigger
            }
            .some
        }

      def pullEvents(upperBound: Bound): F[Map[PeerId, List[(Ordinal, Event)]]] =
        upperBound.toList.traverse {
          case (peerId, peerBound) =>
            eventsR(peerId).modify { maybePeerEvents =>
              maybePeerEvents.traverse { peerEvents =>
                val (eventsAboveBound, pulledEvents) = peerEvents.events.partition {
                  case (eventOrdinal, _) => eventOrdinal > peerBound
                }
                val updatedPeerEvents = peerEvents
                  .focus(_.events)
                  .replace(eventsAboveBound)
                  .focus(_.trigger)
                  .modify(_.filter(_ > peerBound))

                (pulledEvents, updatedPeerEvents)
              }.swap
            }.map((peerId, _))
        }.map(_.toMap)

      def getUpperBound: F[Bound] =
        for {
          peerIds <- eventsR.keys
          bound <- peerIds.traverseFilter { peerId =>
            eventsR(peerId).get.map { maybePeerEvents =>
              maybePeerEvents.flatMap { peerEvents =>
                peerEvents.events.map(_._1).maximumOption.map((peerId, _))
              }
            }
          }
        } yield bound.toMap

      def addFacility(peerId: PeerId, key: Key, facility: Facility): F[ConsensusResources[Artifact]] =
        updateResources(key) { resources =>
          val (proposedFacilitators, updatedResources) = resources
            .focus(_.peerDeclarationsMap)
            .at(peerId)
            .modifyA { maybePeerDeclaration =>
              maybePeerDeclaration
                .getOrElse(PeerDeclarations.empty)
                .focus(_.facility)
                .modifyA { maybeFacility =>
                  val facility2 = maybeFacility.getOrElse(facility)
                  (facility2.facilitators, facility2.some)
                }
                .map(_.some)
            }

          updatedResources
            .focus(_.proposedFacilitators)
            .modify(_.union(proposedFacilitators))
        }

      def addProposal(peerId: PeerId, key: Key, proposal: Proposal): F[ConsensusResources[Artifact]] =
        updatePeerDeclaration(key, peerId) { peerDeclaration =>
          peerDeclaration.focus(_.proposal).modify(_.orElse(proposal.some))
        }

      def addSignature(peerId: PeerId, key: Key, signature: MajoritySignature): F[ConsensusResources[Artifact]] =
        updatePeerDeclaration(key, peerId) { peerDeclaration =>
          peerDeclaration.focus(_.signature).modify(_.orElse(signature.some))
        }

      def addArtifact(key: Key, artifact: Artifact): F[ConsensusResources[Artifact]] =
        artifact.hashF.flatMap { hash =>
          updateResources(key) { resources =>
            resources
              .focus(_.artifacts)
              .at(hash)
              .replace(artifact.some)
          }
        }

      def addRemovedFacilitator(key: Key, facilitator: PeerId): F[ConsensusResources[Artifact]] =
        updateResources(key) { resources =>
          resources
            .focus(_.removedFacilitators)
            .modify(_ + facilitator)
        }

      private def updatePeerDeclaration(key: Key, peerId: PeerId)(f: PeerDeclarations => PeerDeclarations) =
        updateResources(key) { resources =>
          resources
            .focus(_.peerDeclarationsMap)
            .at(peerId)
            .modify { maybePeerDeclaration =>
              f(maybePeerDeclaration.getOrElse(PeerDeclarations.empty)).some
            }
        }

      private def updateResources(key: Key)(f: ConsensusResources[Artifact] => ConsensusResources[Artifact]) =
        resourcesR(key).updateAndGet { maybeResource =>
          f(maybeResource.getOrElse(ConsensusResources.empty)).some
        }.flatMap(_.liftTo[F](new RuntimeException("Should never happen")))

    }
}
