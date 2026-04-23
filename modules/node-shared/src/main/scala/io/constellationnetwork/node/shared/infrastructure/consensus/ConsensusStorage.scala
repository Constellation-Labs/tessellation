package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.Order
import cats.effect.Clock
import cats.effect.kernel.{Async, Ref}
import cats.kernel.Next
import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration

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

  // Public (not private[consensus]) because the advancer's buildSignatureTransition
  // calls addSignature(selfId, ...) to locally self-store the node's own MajoritySignature
  // immediately after signing, mirroring the Facility self-store pattern. Without this
  // the local signature only reaches resources.signatures via the gossip round-trip,
  // which produces a 1-3ms race window at the fast-path quorum threshold (see
  // 2026-04-23 ord-10 failure analysis).
  def addSignature(
    peerId: PeerId,
    key: Key,
    signature: MajoritySignature
  ): F[Option[ConsensusResources[Artifact, Kind]]]

  // Public for the same self-store reason as addSignature — see comment above.
  // currency-l0's buildBinaryTransition calls addBinarySignature(selfId, ...) locally
  // right after signing, closing the race where the local BinarySignature only enters
  // resources via gossip round-trip after quorum from peers has already finalized the round.
  def addBinarySignature(
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

  /** Add an `EvictionVote` to the current round's accumulator. First-write-wins per (voter, target): a peer cannot replace their earlier
    * vote with a later one for the same target. Multiple targets per voter are allowed (up to the per-round cap enforced by the emitter).
    */
  private[consensus] def addEvictionVote(
    origin: PeerId,
    key: Key,
    vote: Signed[EvictionVote]
  ): F[Option[ConsensusResources[Artifact, Kind]]]

  /** Store an assembled `EvictionCertificate` so the next proposer can embed it in the Proposal. Multiple certificates can be stored per
    * key (one per evicted target). Cleared on round cleanup + recovery.
    */
  def storeAssembledEvictionCertificate(key: Key, cert: EvictionCertificate): F[Unit]

  /** Read all currently-assembled EvictionCertificates for a key. Empty set if none. */
  def getAssembledEvictionCertificates(key: Key): F[Set[EvictionCertificate]]

  /** Add an `AdmissionVote` to the current round's accumulator (B2). First-write-wins per (voter, target), same as eviction votes. */
  private[consensus] def addAdmissionVote(
    origin: PeerId,
    key: Key,
    vote: Signed[AdmissionVote]
  ): F[Option[ConsensusResources[Artifact, Kind]]]

  /** Store an assembled `AdmissionCertificate` so the next proposer can embed it in the Proposal. Multiple certs per key (one per
    * re-admitted target). Cleared on round cleanup.
    */
  def storeAssembledAdmissionCertificate(key: Key, cert: AdmissionCertificate): F[Unit]

  /** Read all currently-assembled AdmissionCertificates for a key. Empty set if none. */
  def getAssembledAdmissionCertificates(key: Key): F[Set[AdmissionCertificate]]

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

  /** Current monotonic round-attempt id. Bumped on every successful state mutation through `condModifyState`. Emitters of
    * `ConsensusCommand.RoundCompleted` snapshot this value and tag the command so the FSM can drop the command if the round has since
    * advanced (view change / phase transition / new attempt).
    */
  private[consensus] def getRoundAttemptId: F[Long]

  /** Record that `peer` has produced or relayed a consensus declaration at `key`. Monotonic: stored key is `max(existing, seen)`. Unlike
    * `registerPeer`, which records a peer's one-time join ordinal, this map is continuously refreshed from every keyed rumor and gives a
    * live read of where each peer is in consensus. Consumed by lagging/recovery logic to tell whether the cluster has advanced past this
    * node.
    */
  private[consensus] def observePeerAtKey(peerId: PeerId, key: Key): F[Unit]

  /** Snapshot of the observed per-peer tip keys (`max(seen)`). Populated by `observePeerAtKey` on every incoming keyed rumor. Cleared by
    * `clearAllPeerRegistrations`; entries pruned by `pruneStalePeerRegistrations` to match the active peer set.
    */
  private[consensus] def getPeerCurrentKeys: F[Map[PeerId, Key]]

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
      lastOutcomeR <- Ref.of(none[ConsensusOutcomeWrapper])
      timeTriggerR <- Ref.of(none[FiniteDuration])
      observationKeyR <- Ref.of(Option.empty[Key])
      peerRegistrationsR <- Ref.of(Map.empty[PeerId, Key])
      statesR <- MapRef.ofConcurrentHashMap[F, Key, ConsensusState[Key, Status, Outcome, Kind]]()
      resourcesR <- MapRef.ofConcurrentHashMap[F, Key, ConsensusResources[Artifact, Kind]]()
      voteLocksR <- MapRef.ofConcurrentHashMap[F, Key, VoteLock]()
      assembledVccR <- MapRef.ofConcurrentHashMap[F, Key, ViewChangeCertificate]()
      assembledEvictionCertsR <- MapRef.ofConcurrentHashMap[F, Key, Set[EvictionCertificate]]()
      assembledAdmissionCertsR <- MapRef.ofConcurrentHashMap[F, Key, Set[AdmissionCertificate]]()
      // Monotonic counter bumped on every successful state mutation via condModifyState. Used by
      // the FSM to drop stale ConsensusCommand.RoundCompleted commands — one queued before a
      // subsequent view change / phase transition would otherwise wipe the newly-advanced round.
      // Observed in the 2026-04-21 fork-recovery E2E: abandonment at T=0 queued RoundCompleted,
      // view change at T+165s advanced the round to view=1 CollectingSignatures, the stale
      // RoundCompleted fired 104ms before the final signature arrived and dropped the round.
      roundAttemptIdR <- Ref.of[F, Long](0L)
      // Per-peer highest observed key from incoming keyed rumors (declarations, votes, acks,
      // withdraws, artifacts). Feeds live "peer is ahead of me" detection in AbandonmentTracker
      // and StallDetector. Distinct from peerRegistrationsR which records one-time join keys
      // (see Bug B in the 2026-04-21 fork-recovery post-mortem: peersAtHigherKey=0 forever
      // because registered keys never advance as peers progress).
      peerCurrentKeysR <- Ref.of(Map.empty[PeerId, Key])
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

        // All writers to `statesR(key)` arrive through the FSM command loop (ConsensusFSM.handle),
        // which processes one command at a time. `clearAllConsensusState` — the only other writer —
        // is also FSM-driven (StateTransitions + AbandonmentTracker). There is no concurrent writer
        // to serialize against, so no semaphore is needed here. The earlier Semaphore + 30s timeout
        // wrapper caused 259 testnet stalls on 2026-04-21 under load (heavy work inside the critical
        // section backed up the queue); removing the lock eliminates that stall class.
        def condModifyState[B](key: Key)(modifyStateFn: ModifyStateFn[F, Key, Status, Outcome, Kind, B]): F[Option[B]] =
          for {
            maybeState <- statesR(key).get
            maybeResult <- modifyStateFn(maybeState)
            maybeB <- maybeResult.traverse {
              case (newMaybeState, b) =>
                statesR(key).set(newMaybeState) >> roundAttemptIdR.update(_ + 1).as(b)
            }
          } yield maybeB

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

        def addEvictionVote(
          origin: PeerId,
          key: Key,
          vote: Signed[EvictionVote]
        ): F[Option[ConsensusResources[Artifact, Kind]]] =
          updateResources(key) { resources =>
            val target = vote.value.targetPeer
            val currentPerTarget = resources.evictionVotes.getOrElse(target, Map.empty)
            // First-write-wins per (voter, target): if the voter has already cast a vote
            // for this target in this round, keep the original. Prevents a late retransmit
            // or replay from overwriting the original signed commitment.
            val updatedPerTarget = currentPerTarget.get(origin) match {
              case Some(_) => currentPerTarget
              case None    => currentPerTarget.updated(origin, vote)
            }
            val updatedMap = resources.evictionVotes.updated(target, updatedPerTarget)
            resources.copy(evictionVotes = updatedMap)
          }

        def storeAssembledEvictionCertificate(key: Key, cert: EvictionCertificate): F[Unit] =
          assembledEvictionCertsR(key).update {
            case Some(existing) => (existing + cert).some
            case None           => Set(cert).some
          }

        def getAssembledEvictionCertificates(key: Key): F[Set[EvictionCertificate]] =
          assembledEvictionCertsR(key).get.map(_.getOrElse(Set.empty))

        def addAdmissionVote(
          origin: PeerId,
          key: Key,
          vote: Signed[AdmissionVote]
        ): F[Option[ConsensusResources[Artifact, Kind]]] =
          updateResources(key) { resources =>
            val target = vote.value.targetPeer
            val currentPerTarget = resources.admissionVotes.getOrElse(target, Map.empty)
            // First-write-wins per (voter, target) — same semantics as eviction votes.
            val updatedPerTarget = currentPerTarget.get(origin) match {
              case Some(_) => currentPerTarget
              case None    => currentPerTarget.updated(origin, vote)
            }
            val updatedMap = resources.admissionVotes.updated(target, updatedPerTarget)
            resources.copy(admissionVotes = updatedMap)
          }

        def storeAssembledAdmissionCertificate(key: Key, cert: AdmissionCertificate): F[Unit] =
          assembledAdmissionCertsR(key).update {
            case Some(existing) => (existing + cert).some
            case None           => Set(cert).some
          }

        def getAssembledAdmissionCertificates(key: Key): F[Set[AdmissionCertificate]] =
          assembledAdmissionCertsR(key).get.map(_.getOrElse(Set.empty))

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
          resourcesR(key).set(none) >>
            voteLocksR(key).set(none) >>
            assembledVccR(key).set(none) >>
            assembledEvictionCertsR(key).set(none) >>
            assembledAdmissionCertsR(key).set(none)

        def clearResourcesPreservingDeclarations(key: Key): F[Unit] =
          // evictionVotes and assembledEvictionCerts are preserved across abandonment
          // retries because they are round-scoped (keyed by `key`), not view-scoped.
          // A peer's vote to evict a target at round N is still valid on round N retry;
          // clearing would force the voter to re-accumulate N stall cycles each retry,
          // defeating the purpose of the mechanism on unstable rounds. (The `cb2031286`
          // tip-binding check in the cert builder filters stale votes at assembly time
          // anyway, so preserving them is safe even if later retries run at a different
          // tip.)
          //
          // admissionVotes and assembledAdmissionCerts are NOT preserved. Admission votes
          // are based on an instantaneous mesh-chain-tip observation and become stale
          // immediately — preserving them would mean "peer was seen at tip once during
          // this round" rather than "peer is currently at tip" (codex review 2026-04-23,
          // non-blocker correctness item #1). Clearing forces fresh witness evidence for
          // each retry, which keeps the B2 semantics honest.
          updateResources(key) { resources =>
            resources.copy(
              acksMap = Map.empty,
              withdrawalsMap = Map.empty,
              ackKinds = Set.empty,
              artifacts = Map.empty,
              viewChangeVotes = Map.empty,
              proposalQcs = Map.empty,
              admissionVotes = Map.empty
            )
          }.void >>
            voteLocksR(key).set(none) >>
            assembledVccR(key).set(none) >>
            assembledAdmissionCertsR(key).set(none)

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

        def getRoundAttemptId: F[Long] = roundAttemptIdR.get

        def observePeerAtKey(peerId: PeerId, key: Key): F[Unit] =
          peerCurrentKeysR.update { m =>
            m.updatedWith(peerId) {
              case Some(existing) if Order[Key].gteqv(existing, key) => existing.some
              case _                                                 => key.some
            }
          }

        def getPeerCurrentKeys: F[Map[PeerId, Key]] = peerCurrentKeysR.get

        def pruneStaleResources(activeKey: Key): F[Unit] =
          resourcesR.keys.flatMap { keys =>
            // Only prune keys STRICTLY LESS THAN activeKey. Pre-arrived declarations for future
            // rounds (within the `declarationRangeLimit` window) are already admitted by
            // `updateResources` and must survive completion of earlier rounds; wiping them here
            // on every `activeKey` advance erased legitimate pipelined state. Observed 2026-04-24
            // E2E: Facility declarations for round N+1 arriving ~100ms before round N's local
            // finalization were stored in resourcesR(N+1), then deleted when round N completed,
            // leaving the new round N+1 with `progress=3/5 missing=2` forever because the two
            // "missing" peers never retransmitted. StallDetector logged them as missing for 2+
            // minutes despite DECL_RECEIVED entries for all 5 facilities on the same node.
            //
            // The acceptance window in `updateResources` (`[lastOutcome.key, lastOutcome.key +
            // declarationRangeLimit]`) bounds memory growth; preserving future keys here is
            // consistent with that contract.
            keys.filter(Order[Key].lt(_, activeKey)).traverse_(k => resourcesR(k).set(none))
          }

        def pruneStalePeerRegistrations(activePeers: Set[PeerId]): F[Unit] =
          peerRegistrationsR.update(_.view.filterKeys(activePeers.contains).toMap) >>
            peerCurrentKeysR.update(_.view.filterKeys(activePeers.contains).toMap)

        def clearAllPeerRegistrations: F[Unit] =
          peerRegistrationsR.set(Map.empty) >> peerCurrentKeysR.set(Map.empty)

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
            ecsKeys <- assembledEvictionCertsR.keys
            _ <- ecsKeys.traverse_(k => assembledEvictionCertsR(k).set(none))
            acsKeys <- assembledAdmissionCertsR.keys
            _ <- acsKeys.traverse_(k => assembledAdmissionCertsR(k).set(none))
          } yield ()
      }
  }
}
