package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.Order
import cats.effect.Clock
import cats.effect.kernel.{Async, Ref}
import cats.effect.std.Semaphore
import cats.kernel.Next
import cats.syntax.all._

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusStorage.ModifyStateFn
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration._
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.schema.gossip.Ordinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import io.chrisdavenport.mapref.MapRef
import io.circe.Encoder
import monocle.Lens
import monocle.syntax.all._

trait ConsensusStorage[F[_], Event, Key, Artifact, Context, Status, Outcome, Kind] {
  def getState(key: Key): F[Option[ConsensusState[Key, Status, Outcome, Kind]]]

  def condModifyState[B](key: Key)(modifyStateFn: ModifyStateFn[F, Key, Status, Outcome, Kind, B]): F[Option[B]]

  def getResources(key: Key): F[ConsensusResources[Artifact, Kind]]

  private[consensus] def getTimeTrigger: F[Option[FiniteDuration]]

  private[consensus] def setTimeTrigger(time: FiniteDuration): F[Unit]

  def clearTimeTrigger: F[Unit]

  private[consensus] def addArtifact(key: Key, artifact: Artifact)(
    implicit hasher: Hasher[F]
  ): F[Option[ConsensusResources[Artifact, Kind]]]

  // Public: state creators call this with `selfId` at round start so self's Facility is present locally
  // without relying on gossip self-loopback. Rumor handler still uses it for peer-inbound writes.
  def addFacility(peerId: PeerId, key: Key, facility: Facility): F[Option[ConsensusResources[Artifact, Kind]]]

  private[consensus] def addProposal(peerId: PeerId, key: Key, proposal: Proposal): F[Option[ConsensusResources[Artifact, Kind]]]

  private[consensus] def addSignature(
    peerId: PeerId,
    key: Key,
    signature: MajoritySignature
  ): F[Option[ConsensusResources[Artifact, Kind]]]

  private[consensus] def addBinarySignature(
    peerId: PeerId,
    key: Key,
    signature: BinarySignature
  ): F[Option[ConsensusResources[Artifact, Kind]]]

  private[consensus] def addViewChangeVote(
    origin: PeerId,
    key: Key,
    fromView: Long,
    toView: Long,
    vote: Signed[ViewChangeVote]
  ): F[Option[ConsensusResources[Artifact, Kind]]]

  private[consensus] def addProposalQc(key: Key, qc: ProposalQC): F[Option[ConsensusResources[Artifact, Kind]]]

  /** Attempt to atomically lock a local vote for (view, proposalHash). Returns Right(VoteLock) on success, or Left(reason) if the lock
    * would violate the HotStuff-style safety rule.
    */
  def tryLockVote(
    key: Key,
    view: Long,
    proposalHash: Hash,
    effectiveLockedQc: Option[ProposalQC]
  ): F[Either[String, VoteLock]]

  /** Advance the `lockedQc` inside the VoteLock for a key. No-op if the existing QC is at an equal-or-higher view. */
  def advanceLockedQc(key: Key, qc: ProposalQC): F[Unit]

  /** Read the current VoteLock for a key. */
  def getVoteLock(key: Key): F[Option[VoteLock]]

  /** Clear the VoteLock for a key. Called on round cleanup + recovery. */
  def clearVoteLock(key: Key): F[Unit]

  /** Store an assembled ViewChangeCertificate so the new leader's proposal path can embed it. Cleared on round cleanup + recovery. */
  def storeAssembledVcc(key: Key, vcc: ViewChangeCertificate): F[Unit]

  /** Read the currently-assembled VCC for a key, if any. */
  def getAssembledVcc(key: Key): F[Option[ViewChangeCertificate]]

  private[consensus] def addPeerDeclarationAck(
    peerId: PeerId,
    key: Key,
    kind: Kind,
    ack: Set[PeerId]
  ): F[Option[ConsensusResources[Artifact, Kind]]]

  private[consensus] def addWithdrawPeerDeclaration(
    peerId: PeerId,
    key: Key,
    kind: Kind
  ): F[Option[ConsensusResources[Artifact, Kind]]]

  /** Clears all peer declarations, artifacts, and other resources for the given key. Must be called when abandoning a round to prevent
    * stale state from poisoning retries.
    */
  private[consensus] def clearResources(key: Key): F[Unit]

  /** Clear transient round-scoped resources for a key (artifacts, acks, withdrawals, view-change votes, vote locks, assembled VCC) but
    * PRESERVE `peerDeclarationsMap` — the collected Facility / Proposal / MajoritySignature / BinarySignature entries per peer. Used by
    * `abandonRound` so the retry attempt can immediately resume with the previously-collected declarations instead of re-fetching them from
    * peers (which they won't re-send under first-write-wins semantics).
    */
  private[consensus] def clearResourcesPreservingDeclarations(key: Key): F[Unit]

  private[consensus] def trySetInitialConsensusOutcome(data: Outcome): F[Boolean]

  private[consensus] def clearAndGetLastConsensusOutcome: F[Option[Outcome]]

  def getLastConsensusOutcome: F[Option[Outcome]]

  def getLastKey: F[Option[Key]]

  private[consensus] def tryUpdateLastConsensusOutcomeWithCleanup(
    previousLastKey: Previous[Key],
    lastOutcome: Outcome
  ): F[Boolean]

  private[consensus] def getOwnRegistrationKey: F[Option[Key]]

  private[consensus] def getObservationKey: F[Option[Key]]

  private[consensus] def trySetObservationKey(from: Key): F[Boolean]

  private[consensus] def clearObservationKey: F[Unit]

  def getCandidates(key: Key): F[Candidates]

  private[consensus] def registerPeer(peerId: PeerId, key: Key): F[Boolean]

  /** Returns all peer registrations (peerId → key) for lagging node detection. */
  private[consensus] def getPeerRegistrations: F[Map[PeerId, Key]]

  /** Prune stale resources for keys other than the current active key.
    *
    * Over time, abandoned rounds leave behind entries in the resources map for keys that are no longer active. This method removes all
    * resource entries except the current key, preventing unbounded memory growth. Should be called after each successful consensus round.
    */
  private[consensus] def pruneStaleResources(activeKey: Key): F[Unit]

  /** Prune peer registrations for peers no longer in the cluster.
    *
    * peerRegistrationsR is populated by registerPeer but never cleaned up when peers depart. Stale entries corrupt lagging detection in
    * StallDetector (peersAtDifferentKey count includes departed peers) and cause unbounded memory growth. Should be called after each
    * consensus round.
    */
  private[consensus] def pruneStalePeerRegistrations(activePeers: Set[PeerId]): F[Unit]

  /** Clear all peer registrations. Used during recovery download to prevent stale registrations from causing false lagging detection after
    * the node rejoins.
    */
  private[consensus] def clearAllPeerRegistrations: F[Unit]

  /** Clean up state and resources for a key whose outcome conflicted with a concurrent finalization.
    *
    * When tryUpdateLastConsensusOutcomeWithCleanup returns false (another round's outcome was already stored), the finished state for the
    * conflicted key remains in statesR/resourcesR. Without explicit cleanup, these entries accumulate and leak memory. This method removes
    * both the state and resource entries.
    */
  private[consensus] def cleanupConflictedRound(key: Key): F[Unit]

  /** Clear ALL consensus states and resources across all keys.
    *
    * Used during recovery download to ensure no stale state from previous abandoned rounds persists into the fresh post-recovery context.
    * Without this, ghost entries from other ordinals can interfere with the first post-recovery round.
    */
  private[consensus] def clearAllConsensusState: F[Unit]

}

object ConsensusStorage {

  trait ModifyStateFn[F[_], Key, Status, Outcome, Kind, B]
      extends (
        Option[ConsensusState[Key, Status, Outcome, Kind]] => F[Option[(Option[ConsensusState[Key, Status, Outcome, Kind]], B)]]
      )

  def make[F[_]: Async, Event, Key: Order: Next, Artifact: Encoder, Context, Status, Outcome, Kind](
    consensusConfig: ConsensusConfig
  )(implicit _key: Lens[Outcome, Key]): F[ConsensusStorage[F, Event, Key, Artifact, Context, Status, Outcome, Kind]] = {
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
      lastOutcomeR <- Ref.of(none[ConsensusOutcomeWrapper])
      timeTriggerR <- Ref.of(none[FiniteDuration])
      observationKeyR <- Ref.of(Option.empty[Key])
      peerRegistrationsR <- Ref.of(Map.empty[PeerId, Key])
      statesR <- MapRef.ofConcurrentHashMap[F, Key, ConsensusState[Key, Status, Outcome, Kind]]()
      resourcesR <- MapRef.ofConcurrentHashMap[F, Key, ConsensusResources[Artifact, Kind]]()
      voteLocksR <- MapRef.ofConcurrentHashMap[F, Key, VoteLock]()
      assembledVccR <- MapRef.ofConcurrentHashMap[F, Key, ViewChangeCertificate]()
    } yield
      new ConsensusStorage[F, Event, Key, Artifact, Context, Status, Outcome, Kind] {

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

        /** Maximum time to wait for the state update semaphore before failing. Prevents deadlock if a modify function hangs — the semaphore
          * would block all subsequent state updates indefinitely without this timeout.
          */
        private val semaphoreTimeout: FiniteDuration = 30.seconds

        def condModifyState[B](key: Key)(modifyStateFn: ModifyStateFn[F, Key, Status, Outcome, Kind, B]): F[Option[B]] =
          Async[F].timeoutTo(
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
            },
            semaphoreTimeout,
            Async[F].raiseError(
              new java.util.concurrent.TimeoutException(
                s"Consensus state update semaphore acquisition timed out after ${semaphoreTimeout.toSeconds}s"
              )
            )
          )

        def trySetInitialConsensusOutcome(initialOutcome: Outcome): F[Boolean] =
          lastOutcomeR.modify {
            case s @ Some(_) => (s, false)
            case None        => (ConsensusOutcomeWrapper.of(initialOutcome).some, true)
          }

        def clearAndGetLastConsensusOutcome: F[Option[Outcome]] =
          lastOutcomeR.getAndSet(none).map(_.map(_.value))

        def getLastConsensusOutcome: F[Option[Outcome]] =
          lastOutcomeR.get.map(_.map(_.value))

        def getLastKey: F[Option[Key]] =
          lastOutcomeR.get.map(_.map(wrappedOutcome => _key.get(wrappedOutcome.value)))

        private[consensus] def tryUpdateLastConsensusOutcomeWithCleanup(
          previousLastKey: Previous[Key],
          newLastOutcome: Outcome
        ): F[Boolean] =
          lastOutcomeR.modify {
            case Some(lastOutcome) if _key.get(lastOutcome.value) === previousLastKey.a =>
              (ConsensusOutcomeWrapper.of(newLastOutcome).some, true)
            case other =>
              (other, false)
          }.flatTap { result =>
            cleanupStateAndResource(previousLastKey.a).whenA(result)
          }

        private def cleanupStateAndResource(key: Key): F[Unit] =
          condModifyState[Unit](key) { _ =>
            (none[ConsensusState[Key, Status, Outcome, Kind]], ()).some.pure[F]
          }.void >> clearResources(key)

        def addFacility(peerId: PeerId, key: Key, facility: Facility): F[Option[ConsensusResources[Artifact, Kind]]] =
          updatePeerDeclaration(key, peerId) { peerDeclaration =>
            peerDeclaration.focus(_.facility).modify(_.orElse(facility.some))
          }

        def addProposal(peerId: PeerId, key: Key, proposal: Proposal): F[Option[ConsensusResources[Artifact, Kind]]] =
          updatePeerDeclaration(key, peerId) { peerDeclaration =>
            peerDeclaration.focus(_.proposal).modify {
              case None => proposal.some
              case Some(existing) =>
                if (existing.view > proposal.view) existing.some
                else if (existing.view === proposal.view) {
                  if (existing.hash === proposal.hash) existing.some
                  else existing.some // conflicting same-view: reject (log at caller site)
                } else {
                  // higher view replaces if VCC requirement holds (view 0 = none, view > 0 = VCC present)
                  if (proposal.view > 0L && proposal.vcc.isEmpty) existing.some
                  else proposal.some
                }
            }
          }

        def addSignature(peerId: PeerId, key: Key, signature: MajoritySignature): F[Option[ConsensusResources[Artifact, Kind]]] =
          updatePeerDeclaration(key, peerId) { peerDeclaration =>
            peerDeclaration.focus(_.signature).modify {
              case None => signature.some
              case Some(existing) =>
                if (existing.view > signature.view) existing.some
                else if (existing.view === signature.view) existing.some
                else signature.some
            }
          }

        def addViewChangeVote(
          origin: PeerId,
          key: Key,
          fromView: Long,
          toView: Long,
          vote: Signed[ViewChangeVote]
        ): F[Option[ConsensusResources[Artifact, Kind]]] =
          updateResources(key) { resources =>
            val transitionKey = (fromView, toView)
            val currentPerTransition = resources.viewChangeVotes.getOrElse(transitionKey, Map.empty)
            val updatedPerTransition = currentPerTransition.updated(origin, vote)
            val updatedMap = resources.viewChangeVotes.updated(transitionKey, updatedPerTransition)
            resources.copy(viewChangeVotes = updatedMap)
          }

        def addProposalQc(key: Key, qc: ProposalQC): F[Option[ConsensusResources[Artifact, Kind]]] =
          updateResources(key) { resources =>
            val qcKey = (qc.view, qc.proposalHash)
            if (resources.proposalQcs.contains(qcKey)) resources
            else resources.copy(proposalQcs = resources.proposalQcs.updated(qcKey, qc))
          }

        def tryLockVote(
          key: Key,
          view: Long,
          proposalHash: Hash,
          effectiveLockedQc: Option[ProposalQC]
        ): F[Either[String, VoteLock]] =
          voteLocksR(key).modify { maybeLock =>
            val current = maybeLock.getOrElse(VoteLock.empty)
            current.acceptVote(view, proposalHash, effectiveLockedQc) match {
              case Right(newLock) => (newLock.some, Right(newLock))
              case Left(reason)   => (maybeLock, Left(reason))
            }
          }

        def advanceLockedQc(key: Key, qc: ProposalQC): F[Unit] =
          voteLocksR(key).update {
            case Some(lock) => lock.withAdvancedQc(qc).some
            case None       => VoteLock.empty.withAdvancedQc(qc).some
          }

        def getVoteLock(key: Key): F[Option[VoteLock]] =
          voteLocksR(key).get

        def clearVoteLock(key: Key): F[Unit] =
          voteLocksR(key).set(none)

        def storeAssembledVcc(key: Key, vcc: ViewChangeCertificate): F[Unit] =
          assembledVccR(key).set(vcc.some)

        def getAssembledVcc(key: Key): F[Option[ViewChangeCertificate]] =
          assembledVccR(key).get

        def addBinarySignature(peerId: PeerId, key: Key, signature: BinarySignature): F[Option[ConsensusResources[Artifact, Kind]]] =
          updatePeerDeclaration(key, peerId) { peerDeclaration =>
            peerDeclaration.focus(_.binarySignature).modify(_.orElse(signature.some))
          }

        def addPeerDeclarationAck(
          peerId: PeerId,
          key: Key,
          kind: Kind,
          ack: Set[PeerId]
        ): F[Option[ConsensusResources[Artifact, Kind]]] =
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

        def clearResources(key: Key): F[Unit] =
          resourcesR(key).set(none) >> voteLocksR(key).set(none) >> assembledVccR(key).set(none)

        def clearResourcesPreservingDeclarations(key: Key): F[Unit] =
          updateResources(key) { resources =>
            resources.copy(
              acksMap = Map.empty,
              withdrawalsMap = Map.empty,
              ackKinds = Set.empty,
              artifacts = Map.empty,
              viewChangeVotes = Map.empty,
              proposalQcs = Map.empty
            )
          }.void >> voteLocksR(key).set(none) >> assembledVccR(key).set(none)

        def getOwnRegistrationKey: F[Option[Key]] = observationKeyR.get.map(_.map(_.next))

        def getObservationKey: F[Option[Key]] = observationKeyR.get

        def trySetObservationKey(key: Key): F[Boolean] = observationKeyR.getAndUpdate(_.orElse(key.some)).map(_.isEmpty)

        def clearObservationKey: F[Unit] = observationKeyR.set(none)

        def getCandidates(key: Key): F[Candidates] =
          peerRegistrationsR.get.map { peerRegistrations =>
            peerRegistrations.toList.mapFilter {
              // Use <= instead of === so registrations don't expire after 1 ordinal.
              // A peer registered at key N is found at getCandidates(N), getCandidates(N+1), etc.
              // Stale peers are cleaned by pruneStalePeerRegistrations after each round.
              case (peerId, at) if at <= key => peerId.some
              case _                         => none[PeerId]
            }
          }.map(c => Candidates(c.toSet))

        def registerPeer(peerId: PeerId, newKey: Key): F[Boolean] = {
          // Register at both key and key.next. The state creator calls getCandidates(key.next)
          // where key is the ordinal being produced. If a peer registers at N (the last outcome),
          // the next round produces N+1 and looks for candidates at N+2. Without registering at
          // N+1, newly-Ready peers are never found as candidates.
          val keysToRegister = List(newKey, newKey.next)
          keysToRegister.traverse_ { k =>
            peerRegistrationsR.update { peerRegistrations =>
              peerRegistrations
                .focus()
                .at(peerId)
                .modify { maybeKey =>
                  maybeKey
                    .filter(_ > k)
                    .getOrElse(k)
                    .some
                }
            }
          }.as(true)
        }

        def getPeerRegistrations: F[Map[PeerId, Key]] = peerRegistrationsR.get

        def pruneStaleResources(activeKey: Key): F[Unit] =
          resourcesR.keys.flatMap { keys =>
            keys.filterNot(_ === activeKey).traverse_(k => resourcesR(k).set(none))
          }

        def pruneStalePeerRegistrations(activePeers: Set[PeerId]): F[Unit] =
          peerRegistrationsR.update(_.view.filterKeys(activePeers.contains).toMap)

        def clearAllPeerRegistrations: F[Unit] =
          peerRegistrationsR.set(Map.empty)

        def cleanupConflictedRound(key: Key): F[Unit] =
          condModifyState[Unit](key) { _ =>
            (none[ConsensusState[Key, Status, Outcome, Kind]], ()).some.pure[F]
          }.void >> clearResources(key)

        def clearAllConsensusState: F[Unit] =
          for {
            stateKeys <- statesR.keys
            _ <- stateKeys.traverse_(k => statesR(k).set(none))
            resourceKeys <- resourcesR.keys
            _ <- resourceKeys.traverse_(k => resourcesR(k).set(none))
            voteLockKeys <- voteLocksR.keys
            _ <- voteLockKeys.traverse_(k => voteLocksR(k).set(none))
            vccKeys <- assembledVccR.keys
            _ <- vccKeys.traverse_(k => assembledVccR(k).set(none))
          } yield ()
      }
  }
}
