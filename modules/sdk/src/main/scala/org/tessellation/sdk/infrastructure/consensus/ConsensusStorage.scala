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
import cats.syntax.functorFilter._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.traverse._
import cats.syntax.traverseFilter._
import cats.{Eq, Order, Show}

import scala.concurrent.duration.FiniteDuration

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.ext.crypto._
import org.tessellation.schema.gossip.Ordinal
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.infrastructure.consensus.declaration.kind.PeerDeclarationKind
import org.tessellation.sdk.infrastructure.consensus.declaration.{Facility, MajoritySignature, Proposal}

import io.chrisdavenport.mapref.MapRef
import monocle.syntax.all._

trait ConsensusStorage[F[_], Event, Key, Artifact] {

  private[consensus] trait ModifyStateFn[B]
      extends (Option[ConsensusState[Key, Artifact]] => F[Option[(Option[ConsensusState[Key, Artifact]], B)]])

  def getState(key: Key): F[Option[ConsensusState[Key, Artifact]]]

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

  private[consensus] def addArtifact(key: Key, artifact: Artifact): F[ConsensusResources[Artifact]]

  private[consensus] def addFacility(peerId: PeerId, key: Key, facility: Facility): F[ConsensusResources[Artifact]]

  private[consensus] def addProposal(peerId: PeerId, key: Key, proposal: Proposal): F[ConsensusResources[Artifact]]

  private[consensus] def addSignature(peerId: PeerId, key: Key, signature: MajoritySignature): F[ConsensusResources[Artifact]]

  private[consensus] def addPeerDeclarationAck(
    peerId: PeerId,
    key: Key,
    kind: PeerDeclarationKind,
    ack: Set[PeerId]
  ): F[ConsensusResources[Artifact]]

  private[consensus] def setLastKeyAndStatus(key: Key, artifact: Finished[Artifact]): F[Unit]

  private[consensus] def setLastKey(key: Key): F[Unit]

  private[consensus] def getLastKey: F[Option[Key]]

  private[consensus] def getLastKeyAndStatus: F[Option[(Key, Option[Finished[Artifact]])]]

  private[consensus] def tryUpdateLastKeyAndStatusWithCleanup(
    oldKey: Key,
    newKey: Key,
    newArtifact: Finished[Artifact]
  ): F[Boolean]

  private[consensus] def getOwnRegistration: F[Option[Key]]

  private[consensus] def setOwnRegistration(from: Key): F[Unit]

  def getRegisteredPeers(key: Key): F[List[PeerId]]

  private[consensus] def registerPeer(peerId: PeerId, key: Key): F[Boolean]

  private[consensus] def deregisterPeer(peerId: PeerId, key: Key): F[Boolean]

}

object ConsensusStorage {

  def make[F[_]: Async: KryoSerializer, Event, Key: Show: Next: Order, Artifact <: AnyRef: Show: Eq](
    lastKeyAndStatus: Option[(Key, Option[Finished[Artifact]])]
  ): F[ConsensusStorage[F, Event, Key, Artifact]] =
    for {
      stateUpdateSemaphore <- Semaphore[F](1)
      lastKeyAndStatusR <- Ref.of(lastKeyAndStatus)
      timeTriggerR <- Ref.of(none[FiniteDuration])
      ownRegistrationR <- Ref.of(Option.empty[Key])
      peerRegistrationsR <- Ref.of(Map.empty[PeerId, PeerRegistration[Key]])
      eventsR <- MapRef.ofConcurrentHashMap[F, PeerId, PeerEvents[Event]]()
      statesR <- MapRef.ofConcurrentHashMap[F, Key, ConsensusState[Key, Artifact]]()
      resourcesR <- MapRef.ofConcurrentHashMap[F, Key, ConsensusResources[Artifact]]()
    } yield
      new ConsensusStorage[F, Event, Key, Artifact] {

        def getState(key: Key): F[Option[ConsensusState[Key, Artifact]]] =
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

        def setLastKeyAndStatus(key: Key, artifact: Finished[Artifact]): F[Unit] =
          lastKeyAndStatusR.set((key, artifact.some).some)

        def setLastKey(key: Key): F[Unit] =
          lastKeyAndStatusR.set((key, none).some)

        def getLastKey: F[Option[Key]] =
          lastKeyAndStatusR.get.map(_._1F)

        def getLastKeyAndStatus: F[Option[(Key, Option[Finished[Artifact]])]] = lastKeyAndStatusR.get

        private[consensus] def tryUpdateLastKeyAndStatusWithCleanup(
          lastKey: Key,
          newKey: Key,
          newArtifact: Finished[Artifact]
        ): F[Boolean] =
          lastKeyAndStatusR.modify {
            case Some((actualLastKey, _)) if actualLastKey === lastKey =>
              ((newKey, newArtifact.some).some, true)
            case other @ _ =>
              (other, false)
          }.flatTap(_ => cleanupStateAndResource(lastKey))

        private def cleanupStateAndResource(key: Key): F[Unit] =
          condModifyState[Unit](key) { _ =>
            (none[ConsensusState[Key, Artifact]], ()).some.pure[F]
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
          updatePeerDeclaration(key, peerId) { peerDeclaration =>
            peerDeclaration.focus(_.facility).modify(_.orElse(facility.some))
          }

        def addProposal(peerId: PeerId, key: Key, proposal: Proposal): F[ConsensusResources[Artifact]] =
          updatePeerDeclaration(key, peerId) { peerDeclaration =>
            peerDeclaration.focus(_.proposal).modify(_.orElse(proposal.some))
          }

        def addSignature(peerId: PeerId, key: Key, signature: MajoritySignature): F[ConsensusResources[Artifact]] =
          updatePeerDeclaration(key, peerId) { peerDeclaration =>
            peerDeclaration.focus(_.signature).modify(_.orElse(signature.some))
          }

        def addPeerDeclarationAck(peerId: PeerId, key: Key, kind: PeerDeclarationKind, ack: Set[PeerId]): F[ConsensusResources[Artifact]] =
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

        def addArtifact(key: Key, artifact: Artifact): F[ConsensusResources[Artifact]] =
          artifact.hashF.flatMap { hash =>
            updateResources(key) { resources =>
              resources
                .focus(_.artifacts)
                .at(hash)
                .replace(artifact.some)
            }
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

        private def cleanResources(key: Key): F[Unit] =
          resourcesR(key).set(none)

        def getOwnRegistration: F[Option[Key]] = ownRegistrationR.get

        def setOwnRegistration(key: Key): F[Unit] = ownRegistrationR.set(key.some)

        def getRegisteredPeers(key: Key): F[List[PeerId]] =
          peerRegistrationsR.get.map { peerRegistrations =>
            peerRegistrations.toList.mapFilter {
              case (peerId, Registered(at)) if key >= at => peerId.some
              case _                                     => none[PeerId]
            }
          }

        def registerPeer(peerId: PeerId, key: Key): F[Boolean] =
          condReplacePeerRegistration(peerId, Registered(key)) {
            case Registered(at)   => key >= at // `>` part allows for a registration after a quick restart, before peer got deregistered
            case Deregistered(at) => key > at
          }

        def deregisterPeer(peerId: PeerId, key: Key): F[Boolean] =
          condReplacePeerRegistration(peerId, Deregistered(key)) {
            case Registered(at)   => key >= at
            case Deregistered(at) => key === at
          }

        /** Replaces peer registration with `value` when `cond` evaluates to `true` or when registration doesn't exists for a given `peerId`
          * @return
          *   `true` if value has been replaced
          */
        private def condReplacePeerRegistration(
          peerId: PeerId,
          value: PeerRegistration[Key]
        )(cond: PeerRegistration[Key] => Boolean): F[Boolean] =
          peerRegistrationsR.modify { peerRegistrations =>
            val (resultInt, updated) = peerRegistrations
              .focus()
              .at(peerId)
              .modifyA { maybePeerRegistration =>
                maybePeerRegistration
                  .filterNot(cond)
                  .map(curr => (0, curr.some))
                  .getOrElse((1, value.some))
              }
            (updated, resultInt === 1)
          }
      }

  sealed trait PeerRegistration[Key]

  case class Registered[Key](at: Key) extends PeerRegistration[Key]
  case class Deregistered[Key](at: Key) extends PeerRegistration[Key]
}
