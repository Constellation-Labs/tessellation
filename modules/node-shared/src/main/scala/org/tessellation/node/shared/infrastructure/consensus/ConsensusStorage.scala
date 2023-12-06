package org.tessellation.node.shared.infrastructure.consensus

import cats.Order
import cats.effect.kernel.{Async, Ref}
import cats.effect.std.Semaphore
import cats.kernel.Next
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.contravariantSemigroupal._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.functorFilter._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.traverse._
import cats.syntax.traverseFilter._

import scala.concurrent.duration.FiniteDuration

import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.config.types.ConsensusConfig
import org.tessellation.node.shared.infrastructure.consensus.declaration.kind.PeerDeclarationKind
import org.tessellation.node.shared.infrastructure.consensus.declaration.{Facility, MajoritySignature, Proposal}
import org.tessellation.schema.gossip.Ordinal
import org.tessellation.schema.peer.PeerId

import io.chrisdavenport.mapref.MapRef
import monocle.syntax.all._

trait ConsensusStorage[F[_], Event, Key, Artifact, Context] {

  private[consensus] trait ModifyStateFn[B]
      extends (Option[ConsensusState[Key, Artifact, Context]] => F[Option[(Option[ConsensusState[Key, Artifact, Context]], B)]])

  def getState(key: Key): F[Option[ConsensusState[Key, Artifact, Context]]]

  private[consensus] def condModifyState[B](key: Key)(modifyStateFn: ModifyStateFn[B]): F[Option[B]]

  private[consensus] def containsTriggerEvent: F[Boolean]

  private[consensus] def addTriggerEvent(peerId: PeerId, peerEvent: (Ordinal, Event)): F[Unit]

  private[consensus] def addEvent(peerId: PeerId, peerEvent: (Ordinal, Event)): F[Unit]

  private[consensus] def addEvents(events: Map[PeerId, List[(Ordinal, Event)]]): F[Unit]

  private[consensus] def pullEvents(upperBound: Bound): F[Map[PeerId, List[(Ordinal, Event)]]]

  private[consensus] def getUpperBound: F[Bound]

  def getResources(key: Key): F[ConsensusResources[Artifact]]

  private[consensus] def getTimeTrigger: F[Option[FiniteDuration]]

  private[consensus] def setTimeTrigger(time: FiniteDuration): F[Unit]

  private[consensus] def clearTimeTrigger: F[Unit]

  private[consensus] def addArtifact(key: Key, artifact: Artifact): F[Option[ConsensusResources[Artifact]]]

  private[consensus] def addFacility(peerId: PeerId, key: Key, facility: Facility): F[Option[ConsensusResources[Artifact]]]

  private[consensus] def addProposal(peerId: PeerId, key: Key, proposal: Proposal): F[Option[ConsensusResources[Artifact]]]

  private[consensus] def addSignature(
    peerId: PeerId,
    key: Key,
    signature: MajoritySignature
  ): F[Option[ConsensusResources[Artifact]]]

  private[consensus] def addPeerDeclarationAck(
    peerId: PeerId,
    key: Key,
    kind: PeerDeclarationKind,
    ack: Set[PeerId]
  ): F[Option[ConsensusResources[Artifact]]]

  private[consensus] def addWithdrawPeerDeclaration(
    peerId: PeerId,
    key: Key,
    kind: PeerDeclarationKind
  ): F[Option[ConsensusResources[Artifact]]]

  private[consensus] def trySetInitialConsensusOutcome(data: ConsensusOutcome[Key, Artifact, Context]): F[Boolean]

  private[consensus] def clearAndGetLastConsensusOutcome: F[Option[ConsensusOutcome[Key, Artifact, Context]]]

  def getLastConsensusOutcome: F[Option[ConsensusOutcome[Key, Artifact, Context]]]

  def getLastKey: F[Option[Key]]

  private[consensus] def tryUpdateLastConsensusOutcomeWithCleanup(
    previousLastKey: Key,
    lastOutcome: ConsensusOutcome[Key, Artifact, Context]
  ): F[Boolean]

  private[consensus] def getOwnRegistrationKey: F[Option[Key]]

  private[consensus] def getObservationKey: F[Option[Key]]

  private[consensus] def trySetObservationKey(from: Key): F[Boolean]

  private[consensus] def clearObservationKey: F[Unit]

  def getCandidates(key: Key): F[List[PeerId]]

  private[consensus] def registerPeer(peerId: PeerId, key: Key): F[Boolean]

}

object ConsensusStorage {

  def make[F[_]: Async: KryoSerializer, Event, Key: Order: Next, Artifact <: AnyRef, Context](
    consensusConfig: ConsensusConfig
  ): F[ConsensusStorage[F, Event, Key, Artifact, Context]] = {
    case class ConsensusOutcomeWrapper(
      value: ConsensusOutcome[Key, Artifact, Context],
      maxDeclarationKey: Key
    )

    object ConsensusOutcomeWrapper {
      def of(value: ConsensusOutcome[Key, Artifact, Context]): ConsensusOutcomeWrapper =
        ConsensusOutcomeWrapper(value, value.key.nextN(consensusConfig.declarationRangeLimit))
    }

    for {
      stateUpdateSemaphore <- Semaphore[F](1)
      lastOutcomeR <- Ref.of(none[ConsensusOutcomeWrapper])
      timeTriggerR <- Ref.of(none[FiniteDuration])
      observationKeyR <- Ref.of(Option.empty[Key])
      peerRegistrationsR <- Ref.of(Map.empty[PeerId, Key])
      eventsR <- MapRef.ofConcurrentHashMap[F, PeerId, PeerEvents[Event]]()
      statesR <- MapRef.ofConcurrentHashMap[F, Key, ConsensusState[Key, Artifact, Context]]()
      resourcesR <- MapRef.ofConcurrentHashMap[F, Key, ConsensusResources[Artifact]]()
    } yield
      new ConsensusStorage[F, Event, Key, Artifact, Context] {

        def getState(key: Key): F[Option[ConsensusState[Key, Artifact, Context]]] =
          statesR(key).get

        def getResources(key: Key): F[ConsensusResources[Artifact]] =
          resourcesR(key).get.map(_.getOrElse(ConsensusResources.empty))

        def getTimeTrigger: F[Option[FiniteDuration]] =
          timeTriggerR.get

        def setTimeTrigger(time: FiniteDuration): F[Unit] =
          timeTriggerR.set(time.some)

        def clearTimeTrigger: F[Unit] =
          timeTriggerR.set(none)

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

        def trySetInitialConsensusOutcome(initialOutcome: ConsensusOutcome[Key, Artifact, Context]): F[Boolean] =
          lastOutcomeR.modify {
            case s @ Some(_) => (s, false)
            case None        => (ConsensusOutcomeWrapper.of(initialOutcome).some, true)
          }

        def clearAndGetLastConsensusOutcome: F[Option[ConsensusOutcome[Key, Artifact, Context]]] =
          lastOutcomeR.getAndSet(none).map(_.map(_.value))

        def getLastConsensusOutcome: F[Option[ConsensusOutcome[Key, Artifact, Context]]] =
          lastOutcomeR.get.map(_.map(_.value))

        def getLastKey: F[Option[Key]] =
          lastOutcomeR.get.map(_.map(_.value.key))

        private[consensus] def tryUpdateLastConsensusOutcomeWithCleanup(
          previousLastKey: Key,
          newLastOutcome: ConsensusOutcome[Key, Artifact, Context]
        ): F[Boolean] =
          lastOutcomeR.modify {
            case Some(lastOutcome) if lastOutcome.value.key === previousLastKey =>
              (ConsensusOutcomeWrapper.of(newLastOutcome).some, true)
            case other =>
              (other, false)
          }.flatTap { result =>
            cleanupStateAndResource(previousLastKey).whenA(result)
          }

        private def cleanupStateAndResource(key: Key): F[Unit] =
          condModifyState[Unit](key) { _ =>
            (none[ConsensusState[Key, Artifact, Context]], ()).some.pure[F]
          }.void >> cleanResources(key)

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

        private def addEvents(peerId: PeerId, events: List[(Ordinal, Event)], updateTrigger: Boolean): F[Unit] =
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

        def addFacility(peerId: PeerId, key: Key, facility: Facility): F[Option[ConsensusResources[Artifact]]] =
          updatePeerDeclaration(key, peerId) { peerDeclaration =>
            peerDeclaration.focus(_.facility).modify(_.orElse(facility.some))
          }

        def addProposal(peerId: PeerId, key: Key, proposal: Proposal): F[Option[ConsensusResources[Artifact]]] =
          updatePeerDeclaration(key, peerId) { peerDeclaration =>
            peerDeclaration.focus(_.proposal).modify(_.orElse(proposal.some))
          }

        def addSignature(peerId: PeerId, key: Key, signature: MajoritySignature): F[Option[ConsensusResources[Artifact]]] =
          updatePeerDeclaration(key, peerId) { peerDeclaration =>
            peerDeclaration.focus(_.signature).modify(_.orElse(signature.some))
          }

        def addPeerDeclarationAck(
          peerId: PeerId,
          key: Key,
          kind: PeerDeclarationKind,
          ack: Set[PeerId]
        ): F[Option[ConsensusResources[Artifact]]] =
          updateResources(key) { resources =>
            resources
              .focus(_.acksMap)
              .at((peerId, kind))
              .modify { maybeAck =>
                maybeAck.orElse(ack.some)
              }
              .focus(_.ackKinds)
              .modify(_.incl(kind))
          }

        def addWithdrawPeerDeclaration(
          peerId: PeerId,
          key: Key,
          kind: PeerDeclarationKind
        ): F[Option[ConsensusResources[Artifact]]] =
          updateResources(key) { resources =>
            resources
              .focus(_.withdrawalsMap)
              .at(peerId)
              .modify { maybeKind =>
                maybeKind.orElse(kind.some)
              }
          }

        def addArtifact(key: Key, artifact: Artifact): F[Option[ConsensusResources[Artifact]]] =
          artifact.hashF.flatMap { hash =>
            updateResources(key) { resources =>
              resources
                .focus(_.artifacts)
                .at(hash)
                .replace(artifact.some)
            }
          }

        private def updatePeerDeclaration(key: Key, peerId: PeerId)(
          f: PeerDeclarations => PeerDeclarations
        ): F[Option[ConsensusResources[Artifact]]] =
          updateResources(key) { resources =>
            resources
              .focus(_.peerDeclarationsMap)
              .at(peerId)
              .modify { maybePeerDeclaration =>
                f(maybePeerDeclaration.getOrElse(PeerDeclarations.empty)).some
              }
          }

        private def updateResources(
          key: Key
        )(
          f: ConsensusResources[Artifact] => ConsensusResources[Artifact]
        ): F[Option[ConsensusResources[Artifact]]] =
          lastOutcomeR.get.map { maybeOutcomeWrapper =>
            maybeOutcomeWrapper.forall { outcomeWrapper =>
              key >= outcomeWrapper.value.key && key <= outcomeWrapper.maxDeclarationKey
            }
          }.ifM(
            resourcesR(key).updateAndGet { maybeResource =>
              f(maybeResource.getOrElse(ConsensusResources.empty)).some
            },
            none.pure[F]
          )

        private def cleanResources(key: Key): F[Unit] =
          resourcesR(key).set(none)

        def getOwnRegistrationKey: F[Option[Key]] = observationKeyR.get.map(_.map(_.next))

        def getObservationKey: F[Option[Key]] = observationKeyR.get

        def trySetObservationKey(key: Key): F[Boolean] = observationKeyR.getAndUpdate(_.orElse(key.some)).map(_.isEmpty)

        def clearObservationKey: F[Unit] = observationKeyR.set(none)

        def getCandidates(key: Key): F[List[PeerId]] =
          peerRegistrationsR.get.map { peerRegistrations =>
            peerRegistrations.toList.mapFilter {
              case (peerId, at) if key === at => peerId.some
              case _                          => none[PeerId]
            }
          }

        def registerPeer(peerId: PeerId, newKey: Key): F[Boolean] =
          peerRegistrationsR.modify { peerRegistrations =>
            val result = peerRegistrations
              .focus()
              .at(peerId)
              .modify { maybeKey =>
                maybeKey
                  .filter(_ > newKey)
                  .getOrElse(newKey)
                  .some
              }
            (result, result.get(peerId).exists(_ === newKey))
          }
      }
  }
}
