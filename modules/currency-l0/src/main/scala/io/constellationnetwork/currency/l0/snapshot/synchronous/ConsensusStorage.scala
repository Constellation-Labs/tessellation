package io.constellationnetwork.currency.l0.snapshot.synchronous

import cats.Order
import cats.effect.Clock
import cats.effect.kernel.{Async, Ref, Temporal}
import cats.effect.std.Semaphore
import cats.kernel.Next
import cats.syntax.all._

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import io.constellationnetwork.currency.l0.snapshot.synchronous.ConsensusStorage.{ModifyStateFn, ModifyStateWithEffectFn}
import io.constellationnetwork.currency.l0.snapshot.synchronous.declaration._
import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.Hasher

import io.chrisdavenport.mapref.MapRef
import io.circe.Encoder
import monocle.Lens
import monocle.syntax.all._
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait ConsensusStorage[F[_], Event, Key, Artifact, Context, Status, Outcome, Kind] {
  def getState(key: Key): F[Option[ConsensusState[Key, Status, Outcome, Kind]]]

  def condModifyState[B](key: Key)(modifyStateFn: ModifyStateFn[F, Key, Status, Outcome, Kind, B]): F[Option[B]]

  /** Installs a state transition and retains its idempotent follow-up effect in one uncancelable local commit. */
  private[snapshot] def condModifyStateWithEffect[B](key: Key)(
    modifyStateFn: ModifyStateWithEffectFn[F, Key, Status, Outcome, Kind, B]
  ): F[Option[B]]

  /** Retries the exact follow-up effect retained by [[condModifyStateWithEffect]]. */
  private[snapshot] def runRetainedEffect(key: Key): F[Unit]

  def getResources(key: Key): F[ConsensusResources[Artifact, Kind]]

  private[synchronous] def getTimeTrigger: F[Option[FiniteDuration]]

  private[synchronous] def setTimeTrigger(time: FiniteDuration): F[Unit]

  def clearTimeTrigger: F[Unit]

  def addArtifact(key: Key, artifact: Artifact)(
    implicit hasher: Hasher[F]
  ): F[Option[ConsensusResources[Artifact, Kind]]]

  def addFacility(
    peerId: PeerId,
    key: Key,
    facility: Facility,
    expectedDomain: Option[AttemptDomain]
  ): F[Option[ConsensusResources[Artifact, Kind]]]

  def addProposal(
    peerId: PeerId,
    key: Key,
    proposal: Proposal,
    expectedDomain: Option[AttemptDomain]
  ): F[Option[ConsensusResources[Artifact, Kind]]]

  def addSignature(
    peerId: PeerId,
    key: Key,
    signature: MajoritySignature,
    expectedDomain: Option[AttemptDomain]
  ): F[Option[ConsensusResources[Artifact, Kind]]]

  def addBinarySignature(
    peerId: PeerId,
    key: Key,
    signature: BinarySignature,
    expectedDomain: Option[AttemptDomain]
  ): F[Option[ConsensusResources[Artifact, Kind]]]

  private[snapshot] def addPeerDeclarationAck(
    peerId: PeerId,
    key: Key,
    kind: Kind,
    ack: Set[PeerId],
    domain: AttemptDomain,
    expectedDomain: Option[AttemptDomain]
  ): F[Option[ConsensusResources[Artifact, Kind]]]

  /** Removes declarations received before state creation that do not belong to the installed parent/committee domain. */
  private[snapshot] def retainAttemptDomain(key: Key, domain: AttemptDomain): F[Unit]

  private[synchronous] def addWithdrawPeerDeclaration(
    peerId: PeerId,
    key: Key,
    kind: Kind
  ): F[Option[ConsensusResources[Artifact, Kind]]]

  private[snapshot] def trySetInitialConsensusOutcome(data: Outcome): F[Boolean]

  /** Clears only the exact initial generation installed by a failed download handoff. */
  private[snapshot] def resetInitialConsensusOutcome(key: Key): F[Boolean]

  /** Abandons an exact stale generation under the same transition semaphore as phase updates. A retained effect is completed before
    * inspection, and a locally Finished state can be preserved for the ordinary commit path instead of being deleted underneath it.
    */
  private[snapshot] def abandonGenerationIfCurrent(key: Key)(
    preserve: Option[ConsensusState[Key, Status, Outcome, Kind]] => Boolean
  ): F[Boolean]

  private[synchronous] def clearAndGetLastConsensusOutcome: F[Option[Outcome]]

  def getLastConsensusOutcome: F[Option[Outcome]]

  /** Returns the current outcome only when its key exactly matches. Historical private outcomes are not authority and are not served. */
  def getConsensusOutcome(key: Key): F[Option[Outcome]]

  def getLastKey: F[Option[Key]]

  private[synchronous] def tryUpdateLastConsensusOutcomeWithCleanup(
    previousLastKey: Previous[Key],
    lastOutcome: Outcome
  ): F[Boolean]

  private[synchronous] def getOwnRegistrationKey: F[Option[Key]]

  private[synchronous] def getObservationKey: F[Option[Key]]

  private[synchronous] def trySetObservationKey(from: Key): F[Boolean]

  private[synchronous] def clearObservationKey: F[Unit]

  def getCandidates(key: Key): F[Candidates]

  private[synchronous] def registerPeer(peerId: PeerId, key: Key): F[Boolean]

}

object ConsensusStorage {

  private[snapshot] def retainedEffectRetryDelay(failures: Long): FiniteDuration = {
    val exponent = math.min(5L, math.max(0L, failures)).toInt
    math.min(30L, 1L << exponent).toInt.seconds
  }

  trait ModifyStateFn[F[_], Key, Status, Outcome, Kind, B]
      extends (
        Option[ConsensusState[Key, Status, Outcome, Kind]] => F[Option[(Option[ConsensusState[Key, Status, Outcome, Kind]], B)]]
      )

  trait ModifyStateWithEffectFn[F[_], Key, Status, Outcome, Kind, B]
      extends (
        Option[ConsensusState[Key, Status, Outcome, Kind]] => F[
          Option[(Option[ConsensusState[Key, Status, Outcome, Kind]], B, F[Unit])]
        ]
      )

  def make[F[_]: Async, Event, Key: Order: Next, Artifact: Encoder, Context, Status, Outcome, Kind](
    consensusConfig: ConsensusConfig
  )(implicit _key: Lens[Outcome, Key]): F[ConsensusStorage[F, Event, Key, Artifact, Context, Status, Outcome, Kind]] = {
    final case class RetainedEffect(effect: F[Unit], completion: cats.effect.kernel.Deferred[F, Unit])

    case class ConsensusOutcomeWrapper(
      value: Outcome,
      maxDeclarationKey: Key
    )

    object ConsensusOutcomeWrapper {
      def of(value: Outcome): ConsensusOutcomeWrapper =
        ConsensusOutcomeWrapper(value, _key.get(value).nextN(consensusConfig.declarationRangeLimit))
    }

    for {
      stateUpdateSemaphore <- Semaphore[F](1)
      transitionSemaphore <- Semaphore[F](1)
      retainedEffectSemaphore <- Semaphore[F](1)
      lastOutcomeR <- Ref.of(none[ConsensusOutcomeWrapper])
      timeTriggerR <- Ref.of(none[FiniteDuration])
      observationKeyR <- Ref.of(Option.empty[Key])
      peerRegistrationsR <- Ref.of(Map.empty[PeerId, Key])
      retainedEffectsR <- Ref.of(Map.empty[Key, RetainedEffect])
      statesR <- MapRef.ofConcurrentHashMap[F, Key, ConsensusState[Key, Status, Outcome, Kind]]()
      resourcesR <- MapRef.ofConcurrentHashMap[F, Key, ConsensusResources[Artifact, Kind]]()
    } yield
      new ConsensusStorage[F, Event, Key, Artifact, Context, Status, Outcome, Kind] {

        private val logger = Slf4jLogger.getLogger[F]

        def getState(key: Key): F[Option[ConsensusState[Key, Status, Outcome, Kind]]] =
          statesR(key).get

        def getResources(key: Key): F[ConsensusResources[Artifact, Kind]] = for {
          resources <- resourcesR(key).get
          emptyResources <- ConsensusResources.empty[F, Artifact, Kind]
          result = resources.getOrElse(emptyResources)
        } yield result

        def getTimeTrigger: F[Option[FiniteDuration]] =
          timeTriggerR.get

        def setTimeTrigger(time: FiniteDuration): F[Unit] =
          timeTriggerR.set(time.some)

        def clearTimeTrigger: F[Unit] =
          timeTriggerR.set(none)

        def condModifyState[B](key: Key)(modifyStateFn: ModifyStateFn[F, Key, Status, Outcome, Kind, B]): F[Option[B]] =
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

        def condModifyStateWithEffect[B](key: Key)(
          modifyStateFn: ModifyStateWithEffectFn[F, Key, Status, Outcome, Kind, B]
        ): F[Option[B]] =
          transitionSemaphore.permit.use { _ =>
            // A second rumor callback cannot advance S(n+1) while E(n) is still
            // pending. The wait and the following state/effect commit share this
            // permit, closing the check-then-commit race between phase callbacks.
            runRetainedEffect(key) >> Async[F].uncancelable { poll =>
              stateUpdateSemaphore.permit.use { _ =>
                for {
                  (maybeState, setter) <- statesR(key).access
                  maybeResult <- poll(modifyStateFn(maybeState))
                  maybeB <- maybeResult.traverse {
                    case (newState, b, effect) =>
                      for {
                        existing <- retainedEffectsR.get.map(_.contains(key))
                        // This guard is checked before the state setter. Even if a
                        // future refactor violates the transition semaphore invariant,
                        // it can fail only before committing S(n+1), never after.
                        _ <- Async[F].raiseWhen(existing)(
                          new IllegalStateException(
                            s"Cannot replace unfinished retained synchronous Currency effect for key=$key"
                          )
                        )
                        completion <- cats.effect.kernel.Deferred[F, Unit]
                        retained = RetainedEffect(effect, completion)
                        updated <- setter(newState)
                        _ <- Async[F].raiseUnless(updated)(
                          new Throwable(
                            "Failed consensus state update, all consensus state updates should be sequenced with a semaphore"
                          )
                        )
                        _ <- retainedEffectsR.update(_.updated(key, retained))
                        // Exactly one owner is started for this retained transition. Other callers await
                        // the same Deferred through runRetainedEffect; they never create retry fibers.
                        _ <- Async[F].start(retryRetainedEffect(key, retained, failures = 0L)).void
                      } yield b
                  }
                } yield maybeB
              }
            }
          }

        def runRetainedEffect(key: Key): F[Unit] =
          retainedEffectsR.get.flatMap(_.get(key).traverse_(_.completion.get))

        private def retryRetainedEffect(key: Key, retained: RetainedEffect, failures: Long): F[Unit] =
          retainedEffectSemaphore.permit.use(_ => retained.effect.attempt).flatMap {
            case Right(_) =>
              // Remove before releasing waiters so a subsequent transition can retain its own
              // effect without racing the completed generation.
              retainedEffectsR.update(_ - key) >> retained.completion.complete(()).void
            case Left(error) =>
              val delay = retainedEffectRetryDelay(failures)
              // Attempts are deliberately unbounded while their cadence is bounded.
              // Releasing completion after a failed causal effect would allow the next
              // phase/outcome to overtake persistence. Known exact-install rejection
              // moves the node to WaitingForDownload, whose storage repair can make a
              // later retry succeed without weakening this ordering boundary.
              logger.warn(error)(
                s"Retrying retained synchronous Currency phase effect for key=$key failures=${failures + 1L} delay=$delay"
              ) >>
                Temporal[F].sleep(delay) >>
                retryRetainedEffect(key, retained, if (failures === Long.MaxValue) failures else failures + 1L)
          }

        def trySetInitialConsensusOutcome(initialOutcome: Outcome): F[Boolean] =
          lastOutcomeR.modify {
            case s @ Some(_) => (s, false)
            case None        => (ConsensusOutcomeWrapper.of(initialOutcome).some, true)
          }

        def resetInitialConsensusOutcome(key: Key): F[Boolean] =
          lastOutcomeR.modify {
            case Some(current) if _key.get(current.value) === key => none[ConsensusOutcomeWrapper] -> true
            case current                                          => current -> false
          }.flatTap(reset => cleanupStateAndResource(key.next).whenA(reset))

        def abandonGenerationIfCurrent(key: Key)(
          preserve: Option[ConsensusState[Key, Status, Outcome, Kind]] => Boolean
        ): F[Boolean] =
          transitionSemaphore.permit.use { _ =>
            val nextKey = key.next

            runRetainedEffect(nextKey) >> statesR(nextKey).get.flatMap { currentState =>
              if (preserve(currentState)) false.pure[F]
              else
                lastOutcomeR.modify {
                  case Some(current) if _key.get(current.value) === key => none[ConsensusOutcomeWrapper] -> true
                  case current                                          => current -> false
                }
                  .flatTap(abandoned => cleanupStateAndResource(nextKey).whenA(abandoned))
            }
          }

        def clearAndGetLastConsensusOutcome: F[Option[Outcome]] =
          lastOutcomeR.getAndSet(none).flatMap(_.fold(none[Outcome].pure[F])(_.value.some.pure[F]))

        def getLastConsensusOutcome: F[Option[Outcome]] =
          lastOutcomeR.get.map(_.map(_.value))

        def getConsensusOutcome(key: Key): F[Option[Outcome]] =
          lastOutcomeR.get.map(_.filter(outcome => _key.get(outcome.value) === key).map(_.value))

        def getLastKey: F[Option[Key]] =
          lastOutcomeR.get.map(_.map(wrappedOutcome => _key.get(wrappedOutcome.value)))

        private[synchronous] def tryUpdateLastConsensusOutcomeWithCleanup(
          previousLastKey: Previous[Key],
          newLastOutcome: Outcome
        ): F[Boolean] =
          lastOutcomeR.modify {
            case Some(lastOutcome) if _key.get(lastOutcome.value) === previousLastKey.a =>
              (ConsensusOutcomeWrapper.of(newLastOutcome).some, true)
            case other =>
              (other, false)
          }.flatTap(result => cleanupStateAndResource(previousLastKey.a).whenA(result))

        private def cleanupStateAndResource(key: Key): F[Unit] =
          condModifyState[Unit](key) { _ =>
            (none[ConsensusState[Key, Status, Outcome, Kind]], ()).some.pure[F]
          }.void >> cleanResources(key) >> retainedEffectsR.update(_ - key)

        def addFacility(
          peerId: PeerId,
          key: Key,
          facility: Facility,
          expectedDomain: Option[AttemptDomain]
        ): F[Option[ConsensusResources[Artifact, Kind]]] =
          updatePeerDeclaration(key, peerId) { peerDeclaration =>
            peerDeclaration.focus(_.facility).modify(updateDeclaration(_, facility, expectedDomain))
          }

        def addProposal(
          peerId: PeerId,
          key: Key,
          proposal: Proposal,
          expectedDomain: Option[AttemptDomain]
        ): F[Option[ConsensusResources[Artifact, Kind]]] =
          updatePeerDeclaration(key, peerId) { peerDeclaration =>
            peerDeclaration.focus(_.proposal).modify(updateDeclaration(_, proposal, expectedDomain))
          }

        def addSignature(
          peerId: PeerId,
          key: Key,
          signature: MajoritySignature,
          expectedDomain: Option[AttemptDomain]
        ): F[Option[ConsensusResources[Artifact, Kind]]] =
          updatePeerDeclaration(key, peerId) { peerDeclaration =>
            peerDeclaration.focus(_.signature).modify(updateDeclaration(_, signature, expectedDomain))
          }

        def addBinarySignature(
          peerId: PeerId,
          key: Key,
          signature: BinarySignature,
          expectedDomain: Option[AttemptDomain]
        ): F[Option[ConsensusResources[Artifact, Kind]]] =
          updatePeerDeclaration(key, peerId) { peerDeclaration =>
            peerDeclaration.focus(_.binarySignature).modify(updateDeclaration(_, signature, expectedDomain))
          }

        private def updateDeclaration[D <: PeerDeclaration](
          current: Option[D],
          incoming: D,
          expectedDomain: Option[AttemptDomain]
        ): Option[D] =
          expectedDomain match {
            case Some(expected) if incoming.domain =!= expected => current
            case Some(expected)                                 => current.filter(_.domain === expected).orElse(incoming.some)
            case None                                           => current.orElse(incoming.some)
          }

        def addPeerDeclarationAck(
          peerId: PeerId,
          key: Key,
          kind: Kind,
          ack: Set[PeerId],
          domain: AttemptDomain,
          expectedDomain: Option[AttemptDomain]
        ): F[Option[ConsensusResources[Artifact, Kind]]] =
          if (expectedDomain.exists(_ =!= domain)) none[ConsensusResources[Artifact, Kind]].pure[F]
          else
            updateResources(key) { resources =>
              resources
                .focus(_.acksMap)
                .at((peerId, kind))
                .modify { maybeAck =>
                  maybeAck.filter(_._1 === domain).orElse((domain -> ack).some)
                }
                .focus(_.ackKinds)
                .modify(_.incl(kind))
            }

        def retainAttemptDomain(key: Key, domain: AttemptDomain): F[Unit] =
          updateResources(key) { resources =>
            val declarations = resources.peerDeclarationsMap.flatMap {
              case (peerId, peerDeclarations) =>
                val retained = PeerDeclarations(
                  peerDeclarations.facility.filter(_.domain === domain),
                  peerDeclarations.proposal.filter(_.domain === domain),
                  peerDeclarations.signature.filter(_.domain === domain),
                  peerDeclarations.binarySignature.filter(_.domain === domain)
                )
                Option.when(retained =!= PeerDeclarations.empty)(peerId -> retained)
            }
            val acks = resources.acksMap.filter { case (_, (ackDomain, _)) => ackDomain === domain }
            resources.copy(peerDeclarationsMap = declarations, acksMap = acks)
          }.void

        def addWithdrawPeerDeclaration(
          peerId: PeerId,
          key: Key,
          kind: Kind
        ): F[Option[ConsensusResources[Artifact, Kind]]] =
          updateResources(key) { resources =>
            resources
              .focus(_.withdrawalsMap)
              .at(peerId)
              .modify { maybeKind =>
                maybeKind.orElse(kind.some)
              }
          }

        def addArtifact(key: Key, artifact: Artifact)(implicit hasher: Hasher[F]): F[Option[ConsensusResources[Artifact, Kind]]] =
          artifact.hash.flatMap { hash =>
            updateResources(key) { resources =>
              resources
                .focus(_.artifacts)
                .at(hash)
                .replace(artifact.some)
            }
          }

        private def updatePeerDeclaration(key: Key, peerId: PeerId)(
          f: PeerDeclarations => PeerDeclarations
        ): F[Option[ConsensusResources[Artifact, Kind]]] =
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
          f: ConsensusResources[Artifact, Kind] => ConsensusResources[Artifact, Kind]
        ): F[Option[ConsensusResources[Artifact, Kind]]] =
          lastOutcomeR.get.flatMap { maybeOutcomeWrapper =>
            val allowUpdate = maybeOutcomeWrapper.forall { outcomeWrapper =>
              key >= _key.get(outcomeWrapper.value) && key <= outcomeWrapper.maxDeclarationKey
            }

            if (allowUpdate) {
              for {
                now <- Clock[F].monotonic
                emptyResources <- ConsensusResources.empty[F, Artifact, Kind]
                updated <- resourcesR(key).updateAndGet { maybeResource =>
                  val current = maybeResource.getOrElse(emptyResources)
                  Some(f(current).copy(updatedAt = now))
                }
              } yield updated
            } else {
              none[ConsensusResources[Artifact, Kind]].pure[F]
            }
          }

        private def cleanResources(key: Key): F[Unit] =
          resourcesR(key).set(none)

        def getOwnRegistrationKey: F[Option[Key]] = observationKeyR.get.map(_.map(_.next))

        def getObservationKey: F[Option[Key]] = observationKeyR.get

        def trySetObservationKey(key: Key): F[Boolean] = observationKeyR.getAndUpdate(_.orElse(key.some)).map(_.isEmpty)

        def clearObservationKey: F[Unit] = observationKeyR.set(none)

        def getCandidates(key: Key): F[Candidates] =
          peerRegistrationsR.get.map { peerRegistrations =>
            peerRegistrations.toList.mapFilter {
              case (peerId, at) if key === at => peerId.some
              case _                          => none[PeerId]
            }
          }.map(c => Candidates(c.toSet))

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
