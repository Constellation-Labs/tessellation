package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.effect.Clock
import cats.effect.kernel.{Async, Ref}
import cats.kernel.Next
import cats.syntax.all._
import cats.{Eq, Order}

import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusStorage.ModifyStateFn
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration._
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import io.chrisdavenport.mapref.MapRef
import io.circe.Encoder
import monocle.Lens
import monocle.syntax.all._

trait ConsensusStorage[F[_], Event, Key, Artifact, Context, Status, Outcome, Kind] {
  private[consensus] def legacyViewChangePolicy: LegacyViewChangePolicy

  def getState(key: Key): F[Option[ConsensusState[Key, Status, Outcome, Kind]]]

  def condModifyState[B](key: Key)(modifyStateFn: ModifyStateFn[F, Key, Status, Outcome, Kind, B]): F[Option[B]]

  /** Atomically installs a state transition together with its exact process-local post-commit effect, then runs that effect.
    *
    * The effect remains registered when it fails or is cancelled and is replayed by the next `resumePendingStateEffect`/state update. This
    * closes the otherwise terminal gap where `Finished` (or any other next phase) is visible but persistence/gossip failed after the state
    * write. The closure is deliberately local-only: consensus state is volatile across process restart, and Currency L0's Finished status
    * does not retain enough data to reconstruct its signed binary effect without changing the public schema.
    */
  def condModifyStateWithSideEffect[B](key: Key)(
    modifyStateFn: ModifyStateFn[F, Key, Status, Outcome, Kind, (B, F[Unit])]
  ): F[Option[B]]

  /** Replay the exact post-commit effect retained for the current state-attempt, if any. Stale effects are discarded without execution. */
  def resumePendingStateEffect(key: Key): F[Unit]

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

  // Public for the same exact-self-store reason as Facility and signatures. A leader must
  // install the Proposal it captured before direct delivery; otherwise a missing self-loopback
  // can enter the re-spread path and rebuild a different certificate envelope for the same view.
  def addProposal(peerId: PeerId, key: Key, proposal: Proposal): F[Option[ConsensusResources[Artifact, Kind]]]

  // Public (not private[consensus]) because the advancer's buildSignatureTransition
  // calls addSignature(selfId, ...) to locally self-store the node's own MajoritySignature
  // immediately after signing, mirroring the Facility self-store pattern. Without this
  // the local signature only reaches resources.signatures via the gossip round-trip,
  // which produces a 1-3ms race window at the fast-path quorum threshold (see
  // ord-10 failure analysis).
  def addSignature(
    peerId: PeerId,
    key: Key,
    signature: MajoritySignature
  ): F[Option[ConsensusResources[Artifact, Kind]]]

  // Public for the same self-store reason as addSignature — see comment above.
  // currency-l0's certified-binary transition calls addBinarySignature(selfId, ...) locally
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

  private[consensus] def addTimeoutVote(
    origin: PeerId,
    key: Key,
    fromView: Long,
    toView: Long,
    vote: Signed[TimeoutVote]
  ): F[Option[ConsensusResources[Artifact, Kind]]]

  private[consensus] def storeTimeoutCertificate(key: Key, cert: TimeoutCertificate): F[Option[ConsensusResources[Artifact, Kind]]]

  private[consensus] def markTimeoutCertificateApplyScheduled(key: Key, lastSnapshotHash: Hash, fromView: Long, toView: Long): F[Boolean]

  /** Exact transition check used by the serialized abandon drain. A broad "any marker" predicate would let a stale view suppress a later
    * attempt at the same key.
    */
  private[consensus] def isTimeoutCertificateApplyScheduled(
    key: Key,
    lastSnapshotHash: Hash,
    fromView: Long,
    toView: Long
  ): F[Boolean]

  private[consensus] def addProposalQc(key: Key, qc: ProposalQC): F[Option[ConsensusResources[Artifact, Kind]]]

  /** Attempt to atomically lock a local vote for (view, proposalHash). Returns Right(VoteLock) on success, or Left(VoteRejection) if the
    * lock would violate the HotStuff-style safety rule.
    */
  def tryLockVote(
    key: Key,
    view: Long,
    proposalHash: Hash,
    effectiveLockedQc: Option[ProposalQC]
  ): F[Either[VoteRejection, VoteLock]]

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

  /** Mark a VCC transition as already scheduled for delayed apply.
    *
    * The chain anchor is the parent snapshot hash carried by every VCV. It prevents fork/rollback contexts that reuse the same ordinal and
    * view pair from suppressing each other.
    */
  private[consensus] def markAssembledVccApplyScheduled(key: Key, lastSnapshotHash: Hash, fromView: Long, toView: Long): F[Boolean]

  /** Exact transition check used by the serialized abandon drain. */
  private[consensus] def isAssembledVccApplyScheduled(
    key: Key,
    lastSnapshotHash: Hash,
    fromView: Long,
    toView: Long
  ): F[Boolean]

  /** Mark an assembled VCC rumor as already received from a peer. Returns true only for the first receipt of that anchored transition from
    * origin.
    *
    * Origin is intentionally part of the key: processing is bounded by distinct senders, not globally O(1), so redundant cert relays can
    * cover gossip loss without recreating the unbounded receipt storm.
    */
  private[consensus] def markAssembledVccReceived(
    key: Key,
    origin: PeerId,
    lastSnapshotHash: Hash,
    fromView: Long,
    toView: Long,
    voteSigners: Set[PeerId]
  ): F[Boolean]

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

  /** Clear transient round-scoped resources for a same-key retry. Global L0's fail-closed bridge retains only round-scoped Facility
    * declarations. Currency L0's `PreserveLegacy` policy retains the rc.7 declaration map; its consumers apply attempt-domain filtering and
    * its rumor path may not retransmit a declaration that this node already observed. Vote locks and certified view-change material are
    * policy-scoped; only a real recovery/download boundary may release a Global L0 same-key vote lock.
    */
  private[consensus] def clearResourcesPreservingDeclarations(key: Key): F[Unit]

  /** Drop attempt-bound declaration slots before entering a certified higher view. Facility is intentionally retained: it is round-scoped
    * on the current wire format and is retransmitted independently. Proposal, MajoritySignature, and BinarySignature are all explicitly
    * view-scoped.
    */
  private[consensus] def pruneAttemptDeclarationsForView(key: Key, minViewToKeep: Long): F[Unit]

  /** Drop entries in `peerDeclarationsMap[*].proposal` where the stored proposal's view is below `minViewToKeep` AND has no view cert.
    *
    * These slots are guaranteed never to validate: `ProposalVccValidator` only bypasses the missing-VCC check at exact match `proposalView
    * \== initialViewNumber` (alpha.90 seed-view) or in solo-core mode. Once `initialViewNumber` has advanced past the stored view, the slot
    * will be rejected on every CollectingProposals re-evaluation forever -- and `addProposal`'s first-write-wins for
    * higher-view-without-cert means a fresh broadcast cannot replace it. This is the alpha.92 stale-proposal deadlock (see
    * `project_alpha92_wedge_may21.md`): cluster wedged at ord 3127095 for ~9h with .193 logging 10,333 `view16_proposal_missing_vcc`
    * rejections against its own frozen slot, no path to self-heal short of operator restart.
    *
    * Called by the state advancer's `logVccReject` when the rejection IS the stale-slot pattern, and idempotently by the abandonment path
    * once `initialViewNumber` for the next attempt is known. Public (not private[consensus]) because the dag-l0 and currency-l0 advancers
    * live outside this package and need to invoke it from the validation-failure path.
    */
  def pruneStaleProposalSlots(key: Key, minViewToKeep: Long): F[Unit]

  /** Alpha.97 stale-local-view rejection counter. Called by each layer's advancer from `logVccReject` when the rejection signature
    * indicates this node's local view-state has fallen behind (`view{N}_proposal_missing_vcc` outside the stale-slot pattern, or
    * `vcc_view_mismatch`). Returns the new tally for `key`. Resets to 1 when a different key is observed.
    */
  def tickStaleLocalViewAtSameKey(key: Key): F[Int]

  def getStaleLocalViewAtSameKey(key: Key): F[Int]

  def clearStaleLocalViewAtSameKey: F[Unit]

  /** Alpha.97 soft-reset book-keeping. Counts how many in-place soft resets the caller has performed at the same key. Caller checks this
    * against `config.maxSoftResetsAtSameKey` to decide whether to attempt another soft reset or escalate to heavy Download. Resets on key
    * advance.
    */
  def tickSoftResetAtSameKey(key: Key): F[Int]

  def getSoftResetCountAtSameKey(key: Key): F[Int]

  /** Returns the per-peer declarations map for `key`. Used by the layer advancer's soft-reset gate to inspect which peers have non-empty
    * `facility` / `proposal` entries, and cross-reference those peers against `clusterStorage.getResponsivePeers` to check for Ready
    * bootstrap sources. Exposed because the gate needs both consensus-resources data (here) and cluster state (in clusterStorage) which the
    * advancer has access to but ConsensusStorage does not.
    */
  def getPeerDeclarations(key: Key): F[Map[PeerId, PeerDeclarations]]

  /** Alpha.97 in-place soft reset for `key`. Returns false without modifying state once this node has voted, entered a later view, or
    * observed a certified view advance. Otherwise wipes volatile round state so the FSM re-creates a fresh `ConsensusState` (with the
    * cluster's current view) on the next `StartRound`:
    *   - the stored `ConsensusState` for `key` is removed (so the state creator runs again from scratch -- views, leader, status,
    *     facilitator set);
    *   - `ConsensusResources` is cleared of artifacts, acks, withdrawals, proposalQcs, admissionVotes, AND -- unlike
    *     `clearResourcesPreservingDeclarations` -- `viewChangeVotes`, `assembledVcc`, and the assembled eviction/admission cert slots, all
    *     of which are anchored to the now-stale local view and would corrupt the rebuild;
    *   - the empty vote lock remains empty; a non-empty lock suppresses the reset entirely;
    *   - only round-scoped Facility declarations are preserved. Attempt-bound Proposal/Majority/Binary slots are cleared.
    *
    * NodeState is intentionally NOT touched -- the peer stays Ready, Core does not lose a member to this reset.
    */
  def softResetRoundState(key: Key): F[Boolean]

  private[consensus] def trySetInitialConsensusOutcome(data: Outcome): F[Boolean]

  private[consensus] def clearAndGetLastConsensusOutcome: F[Option[Outcome]]

  def getLastConsensusOutcome: F[Option[Outcome]]

  def getLastKey: F[Option[Key]]

  private[consensus] def tryUpdateLastConsensusOutcomeWithCleanup(
    previousLastKey: Previous[Key],
    lastOutcome: Outcome
  ): F[ConsensusStorage.OutcomeUpdateResult]

  private[consensus] def getOwnRegistrationKey: F[Option[Key]]

  private[consensus] def getObservationKey: F[Option[Key]]

  private[consensus] def trySetObservationKey(from: Key): F[Boolean]

  private[consensus] def clearObservationKey: F[Unit]

  def getCandidates(key: Key): F[Candidates]

  private[consensus] def registerPeer(peerId: PeerId, key: Key): F[Unit]

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
  /** Process-local state epoch used to bind delayed engine commands. This is not serialized or included in consensus hashes. */
  def getRoundAttemptId: F[Long]

  /** Global attempt epoch at which `key` last installed its current state.
    *
    * Unlike a per-key counter, this token is directly comparable with [[getRoundAttemptId]]. A delayed command is current only when both
    * values match. Historical Finished states intentionally remain readable until the following outcome commits; their older token keeps a
    * late `CheckUpdate` from completing the newer active round.
    */
  private[consensus] def getStateAttemptId(key: Key): F[Option[Long]]

  /** Per-key monotonic generation bumped when attempt-progress evidence changes: Facilities, Proposals, artifact signatures, binary
    * signatures, acknowledgements, withdrawals, or artifacts. Inbound auxiliary admission/eviction/view-change/timeout votes do not bump
    * this generation. A successfully drained local pacemaker request explicitly bumps it once, however, so an abandon sampled before that
    * serialized emission cannot remove state before the already-FIFO-ahead certificate assembly commands run. A queued abandonment carries
    * this alongside the state attempt id so newly arrived phase/finality evidence cannot be erased by an older monitor decision.
    */
  private[consensus] def getResourceGeneration(key: Key): F[Long]

  /** Mark successful local VCV/TC emission as serialized attempt progress. This is deliberately narrower than treating every inbound
    * auxiliary vote as phase progress: it closes RequestViewChange -> AbandonRound -> CheckAssembly queue ordering without allowing rumor
    * traffic to postpone abandonment indefinitely.
    */
  private[consensus] def markPacemakerEmissionProgress(key: Key): F[Unit]

  /** Record that `peer` has produced or relayed a consensus declaration at `key`. Monotonic: stored key is `max(existing, seen)`. Unlike
    * `registerPeer`, which records a peer's one-time join ordinal, this map is continuously refreshed from every keyed rumor and gives a
    * live read of where each peer is in consensus. Consumed by lagging/recovery logic to tell whether the cluster has advanced past this
    * node.
    */
  private[consensus] def observePeerAtKey(peerId: PeerId, key: Key): F[Unit]

  /** Snapshot of the observed per-peer tip keys (`max(seen)`). Populated by `observePeerAtKey` on every incoming keyed rumor. Cleared by
    * `clearAllPeerRegistrations`; entries pruned by `pruneStalePeerRegistrations` to match the active peer set.
    */
  // Public (was private[consensus]): the layer advancer's soft-reset gate
  // (alpha.97) cross-references this with `clusterStorage.getResponsivePeers`
  // to find Ready peers at our key or ahead, matching the existing
  // peersAtHigherKey check pattern in StallDetector / AbandonmentTracker.
  def getPeerCurrentKeys: F[Map[PeerId, Key]]

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

  private final case class PendingStateEffect[F[_]](attemptId: Long, effect: F[Unit])

  sealed trait OutcomeUpdateResult extends Product with Serializable
  object OutcomeUpdateResult {
    case object Advanced extends OutcomeUpdateResult
    case object AlreadyCurrent extends OutcomeUpdateResult
    case object Conflict extends OutcomeUpdateResult
  }

  private[consensus] def retainVoteLockAcrossAbandon(policy: LegacyViewChangePolicy): Boolean =
    policy == LegacyViewChangePolicy.FreezeAfterVote

  private[consensus] def isExactTransitionScheduled(
    scheduled: Option[Set[(Hash, Long, Long)]],
    lastSnapshotHash: Hash,
    fromView: Long,
    toView: Long
  ): Boolean =
    scheduled.exists(_.contains((lastSnapshotHash, fromView, toView)))

  /** Structural attempt-progress comparison used by the queued-abandon epoch. Auxiliary certificate-vote collections are intentionally
    * absent: they cannot advance a phase until their separately serialized assembly/apply command succeeds, and the monitor itself may emit
    * those votes before it queues an abandon. Counting them would make the monitor invalidate its own decision forever.
    */
  private[consensus] def attemptProgressChanged[Artifact, Kind](
    before: ConsensusResources[Artifact, Kind],
    after: ConsensusResources[Artifact, Kind]
  ): Boolean =
    before.peerDeclarationsMap != after.peerDeclarationsMap ||
      before.acksMap != after.acksMap ||
      before.withdrawalsMap != after.withdrawalsMap ||
      before.ackKinds != after.ackKinds ||
      before.artifacts != after.artifacts

  /** Same-key retry retention. A retry may reuse round-scoped Facility evidence, but it must never let a Proposal/Signature from an old
    * attempt occupy the one active per-peer slot for the new attempt.
    */
  private[consensus] def retainFacilityOnly(declarations: PeerDeclarations): PeerDeclarations =
    PeerDeclarations.empty.copy(facility = declarations.facility)

  private[consensus] def declarationsAfterAbandon(
    declarations: PeerDeclarations,
    policy: LegacyViewChangePolicy
  ): PeerDeclarations =
    policy match {
      case LegacyViewChangePolicy.FreezeAfterVote => retainFacilityOnly(declarations)
      case LegacyViewChangePolicy.PreserveLegacy  => declarations
    }

  /** Certified-view pruning over the internal declaration index. Kept pure so the complete retention truth table is regression-tested
    * without duplicating the predicate in tests.
    */
  private[consensus] def pruneAttemptDeclarationsForView(
    declarations: PeerDeclarations,
    minViewToKeep: Long
  ): PeerDeclarations =
    declarations.copy(
      proposal = declarations.proposal.filter(_.view >= minViewToKeep),
      signature = declarations.signature.filter(_.view >= minViewToKeep),
      binarySignature = declarations.binarySignature.filter(_.view >= minViewToKeep)
    )

  private[consensus] def sameKeySoftResetAllowed(
    viewNumber: Int,
    voteLock: Option[VoteLock],
    hasCertifiedAdvance: Boolean
  ): Boolean =
    viewNumber == 0 && !voteLock.exists(_.blocksLegacyViewChange) && !hasCertifiedAdvance

  trait ModifyStateFn[F[_], Key, Status, Outcome, Kind, B]
      extends (
        Option[ConsensusState[Key, Status, Outcome, Kind]] => F[Option[(Option[ConsensusState[Key, Status, Outcome, Kind]], B)]]
      )

  def make[F[_]: Async, Event, Key: Order: Next, Artifact: Encoder, Context, Status, Outcome: Eq, Kind](
    consensusConfig: ConsensusConfig,
    viewChangePolicy: LegacyViewChangePolicy = LegacyViewChangePolicy.PreserveLegacy
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
      staleLocalViewAtSameKeyR <- Ref.of[F, (Option[Key], Int)]((none[Key], 0))
      softResetAtSameKeyR <- Ref.of[F, (Option[Key], Int)]((none[Key], 0))
      statesR <- MapRef.ofConcurrentHashMap[F, Key, ConsensusState[Key, Status, Outcome, Kind]]()
      resourcesR <- MapRef.ofConcurrentHashMap[F, Key, ConsensusResources[Artifact, Kind]]()
      voteLocksR <- MapRef.ofConcurrentHashMap[F, Key, VoteLock]()
      assembledVccR <- MapRef.ofConcurrentHashMap[F, Key, ViewChangeCertificate]()
      assembledVccApplyScheduledR <- MapRef.ofConcurrentHashMap[F, Key, Set[(Hash, Long, Long)]]()
      assembledVccReceiptsR <- MapRef.ofConcurrentHashMap[F, Key, Set[(PeerId, Hash, Long, Long, Set[PeerId])]]()
      timeoutCertificateApplyScheduledR <- MapRef.ofConcurrentHashMap[F, Key, Set[(Hash, Long, Long)]]()
      assembledEvictionCertsR <- MapRef.ofConcurrentHashMap[F, Key, Set[EvictionCertificate]]()
      assembledAdmissionCertsR <- MapRef.ofConcurrentHashMap[F, Key, Set[AdmissionCertificate]]()
      pendingStateEffectsR <- MapRef.ofConcurrentHashMap[F, Key, ConsensusStorage.PendingStateEffect[F]]()
      // Monotonic counter bumped on every successful state mutation via condModifyState. Used by
      // the FSM to drop stale ConsensusCommand.RoundCompleted commands — one queued before a
      // subsequent view change / phase transition would otherwise wipe the newly-advanced round.
      // Observed in the fork-recovery E2E: abandonment at T=0 queued RoundCompleted,
      // view change at T+165s advanced the round to view=1 CollectingSignatures, the stale
      // RoundCompleted fired 104ms before the final signature arrived and dropped the round.
      roundAttemptIdR <- Ref.of[F, Long](0L)
      // Per-key record of the GLOBAL state epoch at which this key last changed. Retained
      // effects compare against this record so an unrelated key cannot invalidate them, while
      // delayed FSM commands additionally compare it with roundAttemptIdR to prove that their
      // key is still the active state transition.
      stateAttemptIdR <- MapRef.ofConcurrentHashMap[F, Key, Long]()
      resourceGenerationR <- MapRef.ofConcurrentHashMap[F, Key, Long]()
      // Per-peer highest observed key from incoming keyed rumors (declarations, votes, acks,
      // withdraws, artifacts). Feeds live "peer is ahead of me" detection in AbandonmentTracker
      // and StallDetector. Distinct from peerRegistrationsR which records one-time join keys
      // (see Bug B in the fork-recovery post-mortem: peersAtHigherKey=0 forever
      // because registered keys never advance as peers progress).
      peerCurrentKeysR <- Ref.of(Map.empty[PeerId, Key])
    } yield
      new ConsensusStorage[F, Event, Key, Artifact, Context, Status, Outcome, Kind] {

        def legacyViewChangePolicy: LegacyViewChangePolicy = viewChangePolicy

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
        // wrapper caused 259 testnet stalls under load (heavy work inside the critical
        // section backed up the queue); removing the lock eliminates that stall class.
        def condModifyState[B](key: Key)(modifyStateFn: ModifyStateFn[F, Key, Status, Outcome, Kind, B]): F[Option[B]] =
          for {
            maybeState <- statesR(key).get
            maybeResult <- modifyStateFn(maybeState)
            maybeB <- maybeResult.traverse {
              case (newMaybeState, b) =>
                Async[F].uncancelable { _ =>
                  statesR(key).set(newMaybeState) >>
                    roundAttemptIdR.modify { current =>
                      val next = current + 1L
                      (next, next)
                    }.flatMap(next => stateAttemptIdR(key).set(next.some)) >>
                    // A plain state mutation supersedes any retained effect from the prior
                    // attempt. The specialized method below installs the replacement effect
                    // in the same uncancelable commit boundary.
                    pendingStateEffectsR(key).set(none)
                }.as(b)
            }
          } yield maybeB

        def condModifyStateWithSideEffect[B](key: Key)(
          modifyStateFn: ModifyStateFn[F, Key, Status, Outcome, Kind, (B, F[Unit])]
        ): F[Option[B]] =
          for {
            maybeState <- statesR(key).get
            maybeResult <- modifyStateFn(maybeState)
            maybeB <- maybeResult.traverse {
              case (newMaybeState, (b, effect)) =>
                Async[F].uncancelable { _ =>
                  for {
                    _ <- statesR(key).set(newMaybeState)
                    attemptId <- roundAttemptIdR.modify { current =>
                      val next = current + 1L
                      (next, next)
                    }
                    _ <- stateAttemptIdR(key).set(attemptId.some)
                    _ <- pendingStateEffectsR(key).set(ConsensusStorage.PendingStateEffect(attemptId, effect).some)
                  } yield b
                }
            }
            _ <- maybeB.traverse_(_ => resumePendingStateEffect(key))
          } yield maybeB

        def resumePendingStateEffect(key: Key): F[Unit] =
          pendingStateEffectsR(key).get.flatMap {
            case None => Async[F].unit
            case Some(pending) =>
              stateAttemptIdR(key).get.map(_.getOrElse(0L)).flatMap { currentAttemptId =>
                if (currentAttemptId =!= pending.attemptId)
                  pendingStateEffectsR(key).update(_.filterNot(_.attemptId === pending.attemptId))
                else
                  // The effect itself remains cancelable. Once it returns successfully, stay
                  // masked through the conditional delete so a delivered effect cannot be
                  // replayed solely because cancellation landed between delivery and cleanup.
                  Async[F].uncancelable { poll =>
                    poll(pending.effect) >>
                      pendingStateEffectsR(key).update(_.filterNot(_.attemptId === pending.attemptId))
                  }
              }
          }

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
        ): F[ConsensusStorage.OutcomeUpdateResult] =
          lastOutcomeR.modify {
            case Some(lastOutcome) if _key.get(lastOutcome.value) === previousLastKey.a =>
              (ConsensusOutcomeWrapper.of(newLastOutcome).some, ConsensusStorage.OutcomeUpdateResult.Advanced)
            case current @ Some(lastOutcome) if lastOutcome.value === newLastOutcome =>
              (current, ConsensusStorage.OutcomeUpdateResult.AlreadyCurrent)
            case other =>
              (other, ConsensusStorage.OutcomeUpdateResult.Conflict)
          }.flatTap { result =>
            cleanupStateAndResource(previousLastKey.a).whenA(result == ConsensusStorage.OutcomeUpdateResult.Advanced)
          }

        private def cleanupStateAndResource(key: Key): F[Unit] =
          // This key is historical: cleaning it must not advance the active round epoch. In
          // particular, outcome N commits while state N is current and removes state N-1. If
          // that removal bumped roundAttemptIdR, the exact ConsensusFinished(N) token would be
          // stale before it was even enqueued.
          statesR(key).set(none) >>
            stateAttemptIdR(key).set(none) >>
            clearResources(key)

        def addFacility(peerId: PeerId, key: Key, facility: Facility): F[Option[ConsensusResources[Artifact, Kind]]] =
          // Latest-write-wins. The previous `.orElse(facility.some)` first-write-wins semantics produced the
          // alpha.92 follow-on wedge at ord 3127110 (project_alpha92_wedge_may21.md): a peer's earlier-view
          // Facility carries the facilitator set known at that point, but the set legitimately rotates across
          // intra-round view-changes (committee shrinkage, eviction certs applied, admissions). The first-stored
          // Facility's `facilitatorsHash` then mismatches every later view's locally-computed hash, triggering
          // the `facilitator_set_mismatch_revalidate` path in `GlobalSnapshotConsensusStateAdvancer.scala:1618`
          // which infinitely withdraws self-signatures. `Facility` has no `view` field so view-based comparison
          // is not available without a schema bump; latest-wins is the smallest correct change.
          //
          // Safety: every received Facility is rumor-signature-verified upstream (`RumorValidator` binds the
          // Facility to its signer's PeerId), so the replacement can only originate from the peer itself --
          // an attacker cannot inject a stale Facility on behalf of someone else. Honest peers only re-emit a
          // Facility after a real view-change, in which case the newer set is what they currently believe.
          updatePeerDeclaration(key, peerId) { peerDeclaration =>
            peerDeclaration.focus(_.facility).replace(facility.some)
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
                  // Higher view replaces only if the view-certificate requirement holds:
                  // view 0 carries no cert, view > 0 carries either VCC or TC.
                  if (proposal.view > 0L && proposal.vcc.isEmpty && proposal.timeoutCertificate.isEmpty) existing.some
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
            // Last-write-wins per verified signer (origin is the verified signer; the relay-overwrite
            // hole the review flagged is closed by the origin===signer guard in RumorHandler.handlePeerVote).
            // NOT first-write-wins: unlike eviction/admission votes, a ViewChangeVote carries a mutable
            // highestKnownQc and an honest node may re-emit with an upgraded QC (no vote-once-on-emit lock),
            // so keeping the latest preserves HotStuff highest-QC carry-forward.
            val updatedPerTransition = currentPerTransition.updated(origin, vote)
            val updatedMap = resources.viewChangeVotes.updated(transitionKey, updatedPerTransition)
            resources.copy(viewChangeVotes = updatedMap)
          }

        def addTimeoutVote(
          origin: PeerId,
          key: Key,
          fromView: Long,
          toView: Long,
          vote: Signed[TimeoutVote]
        ): F[Option[ConsensusResources[Artifact, Kind]]] =
          updateResources(key) { resources =>
            val transitionKey = (fromView, toView)
            val currentPerTransition = resources.timeoutVotes.getOrElse(transitionKey, Map.empty)
            // Last-write-wins per signer (same rationale as addViewChangeVote: a TimeoutVote also carries
            // a mutable highestKnownQc; keep the latest so an upgraded QC is not dropped).
            val updatedPerTransition = currentPerTransition.updated(origin, vote)
            val updatedMap = resources.timeoutVotes.updated(transitionKey, updatedPerTransition)
            resources.copy(timeoutVotes = updatedMap)
          }

        def storeTimeoutCertificate(key: Key, cert: TimeoutCertificate): F[Option[ConsensusResources[Artifact, Kind]]] =
          updateResources(key) { resources =>
            val transitionKey = (cert.fromView, cert.toView)
            if (resources.timeoutCertificates.contains(transitionKey)) resources
            else resources.copy(timeoutCertificates = resources.timeoutCertificates.updated(transitionKey, cert))
          }

        def markTimeoutCertificateApplyScheduled(key: Key, lastSnapshotHash: Hash, fromView: Long, toView: Long): F[Boolean] =
          timeoutCertificateApplyScheduledR(key).modify { maybeScheduled =>
            val transition = (lastSnapshotHash, fromView, toView)
            val scheduled = maybeScheduled.getOrElse(Set.empty[(Hash, Long, Long)])
            if (scheduled.contains(transition)) (maybeScheduled, false)
            else (scheduled.incl(transition).some, true)
          }

        def isTimeoutCertificateApplyScheduled(
          key: Key,
          lastSnapshotHash: Hash,
          fromView: Long,
          toView: Long
        ): F[Boolean] =
          timeoutCertificateApplyScheduledR(key).get.map(
            ConsensusStorage.isExactTransitionScheduled(_, lastSnapshotHash, fromView, toView)
          )

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
        ): F[Either[VoteRejection, VoteLock]] =
          voteLocksR(key).modify { maybeLock =>
            val current = maybeLock.getOrElse(VoteLock.empty)
            current.acceptVote(view, proposalHash, effectiveLockedQc, viewChangePolicy) match {
              case Right(newLock)  => (newLock.some, Right(newLock))
              case Left(rejection) => (maybeLock, Left(rejection))
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

        def markAssembledVccApplyScheduled(key: Key, lastSnapshotHash: Hash, fromView: Long, toView: Long): F[Boolean] =
          assembledVccApplyScheduledR(key).modify { maybeScheduled =>
            val transition = (lastSnapshotHash, fromView, toView)
            val scheduled = maybeScheduled.getOrElse(Set.empty[(Hash, Long, Long)])
            if (scheduled.contains(transition)) (maybeScheduled, false)
            else (scheduled.incl(transition).some, true)
          }

        def isAssembledVccApplyScheduled(
          key: Key,
          lastSnapshotHash: Hash,
          fromView: Long,
          toView: Long
        ): F[Boolean] =
          assembledVccApplyScheduledR(key).get.map(
            ConsensusStorage.isExactTransitionScheduled(_, lastSnapshotHash, fromView, toView)
          )

        def markAssembledVccReceived(
          key: Key,
          origin: PeerId,
          lastSnapshotHash: Hash,
          fromView: Long,
          toView: Long,
          voteSigners: Set[PeerId]
        ): F[Boolean] =
          assembledVccReceiptsR(key).modify { maybeReceipts =>
            val receipt = (origin, lastSnapshotHash, fromView, toView, voteSigners)
            val receipts = maybeReceipts.getOrElse(Set.empty[(PeerId, Hash, Long, Long, Set[PeerId])])
            if (receipts.contains(receipt)) (maybeReceipts, false)
            else (receipts.incl(receipt).some, true)
          }

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
          // Dedup by `targetPeer`: `CheckEvictionAssembly(key, target)` is enqueued once per
          // arriving vote for that target (RumorHandler). Each invocation re-reads votes from
          // storage, re-runs the quorum check, and on success builds and stores a cert. After
          // the first cert is stored, later invocations with the same vote set OR a strict
          // superset still pass quorum and build a cert -- but the new cert can differ from the
          // first by signature-set membership (extra later-arriving votes) so `existing + cert`
          // appends rather than dedupes via case-class equality. The downstream proposal
          // validator then rejects the ECS with `ecs_duplicate_target`, wedging the round.
          // First-write-wins on `targetPeer`: the initial cert already carries quorum sigs; later
          // certs with the same target add no liveness value. This makes the storage operation
          // idempotent on (key, targetPeer) and matches the symmetric dedup applied to
          // assembled admission certificates below.
          assembledEvictionCertsR(key).update {
            case Some(existing) if existing.exists(_.targetPeer === cert.targetPeer) => existing.some
            case Some(existing)                                                      => (existing + cert).some
            case None                                                                => Set(cert).some
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
          // Dedup by `targetPeer`: mirror of eviction-cert storage above. The B2 admission
          // assembly path uses the same vote-arrival -> CheckAdmissionAssembly trigger pattern,
          // so it carries the same duplicate-target risk if not deduped at store time.
          assembledAdmissionCertsR(key).update {
            case Some(existing) if existing.exists(_.targetPeer === cert.targetPeer) => existing.some
            case Some(existing)                                                      => (existing + cert).some
            case None                                                                => Set(cert).some
          }

        def getAssembledAdmissionCertificates(key: Key): F[Set[AdmissionCertificate]] =
          assembledAdmissionCertsR(key).get.map(_.getOrElse(Set.empty))

        def addBinarySignature(peerId: PeerId, key: Key, signature: BinarySignature): F[Option[ConsensusResources[Artifact, Kind]]] =
          // Latest-write-wins, same rationale as `addFacility`. The declaration is explicitly bound to the
          // key's view, proposal hash, and exact binary hash; a later same-peer attempt must be able to replace
          // an obsolete one after a view change. Rumor verification binds the replacement to the same origin.
          updatePeerDeclaration(key, peerId) { peerDeclaration =>
            peerDeclaration.focus(_.binarySignature).replace(signature.some)
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
                update <- resourcesR(key).modify { maybeResource =>
                  val current = maybeResource.getOrElse(emptyResources)
                  val next = f(current).copy(updatedAt = now)
                  (Some(next), (next, ConsensusStorage.attemptProgressChanged(current, next)))
                }
                (updated, attemptProgressChanged) = update
                _ <- resourceGenerationR(key)
                  .update(current => Some(current.getOrElse(0L) + 1L))
                  .whenA(attemptProgressChanged)
              } yield updated.some
            } else {
              none[ConsensusResources[Artifact, Kind]].pure[F]
            }
          }

        def clearResources(key: Key): F[Unit] =
          resourcesR(key).set(none) >>
            resourceGenerationR(key).update(current => Some(current.getOrElse(0L) + 1L)) >>
            pendingStateEffectsR(key).set(none) >>
            voteLocksR(key).set(none) >>
            assembledVccR(key).set(none) >>
            assembledVccApplyScheduledR(key).set(none) >>
            assembledVccReceiptsR(key).set(none) >>
            assembledEvictionCertsR(key).set(none) >>
            assembledAdmissionCertsR(key).set(none) >>
            timeoutCertificateApplyScheduledR(key).set(none)

        def clearResourcesPreservingDeclarations(key: Key): F[Unit] =
          // evictionVotes, assembledEvictionCerts, viewChangeVotes, and assembledVcc
          // are preserved across abandonment retries because their identifying keys
          // remain stable across retry:
          //   - evictionVotes / assembledEvictionCerts: round-scoped (keyed by `key`);
          //     a vote to evict a target at round N is still valid on round N retry.
          //   - viewChangeVotes / assembledVcc: (fromView, toView)-keyed within the
          //     round. A peer's "advance from view N to N+1" assertion remains valid
          //     after retry, and wiping forces partial accumulations (e.g., 4-of-q)
          //     to drop to 0 on each retry. Observed on alpha.81 testnet 2026-05-18:
          //     cluster spent 30+ min stuck at ordinal 3126794 with votes oscillating
          //     between 1 and 4 against a quorum of 5, never closing the certificate
          //     because the abandon-clear race wiped progress every ~45s. The cert
          //     builder filters stale signers at assembly time via the witness-pool
          //     gate (ViewChangeCertificateBuilder), so preserving these is safe
          //     even if facilitators rotate between retries.
          //
          // admissionVotes and assembledAdmissionCerts are NOT preserved. Admission
          // votes are based on an instantaneous mesh-chain-tip observation and become
          // stale immediately. Preserving would assert "peer was seen at tip once
          // during this round" rather than "peer is currently at tip" (non-blocker
          // correctness item #1). Clearing forces fresh witness evidence for each
          // retry, keeping the B2 semantics honest.
          //
          // Declaration retention is layer-policy-specific. GL0 drops attempt-bound slots so
          // its fail-closed retry cannot consume a legacy proposal envelope. Currency retains
          // the rc.7 map because existing rumor delivery may not repeat an already-observed
          // declaration; its attempt-domain checks keep mismatched signatures from counting.
          updateResources(key) { resources =>
            resources.copy(
              peerDeclarationsMap = resources.peerDeclarationsMap.view
                .mapValues(ConsensusStorage.declarationsAfterAbandon(_, viewChangePolicy))
                .toMap,
              acksMap = Map.empty,
              withdrawalsMap = Map.empty,
              ackKinds = Set.empty,
              artifacts = Map.empty,
              proposalQcs = Map.empty,
              admissionVotes = Map.empty
            )
          }.void >>
            // GL0's fail-closed legacy policy retains its lock: forgetting it would let a same-key
            // retry sign a second artifact in the same view. Currency deliberately preserves rc.7
            // retry behavior; its PreserveLegacy policy must clear the old attempt's lock before
            // recreating view 0, or a newly derived proposal is rejected as same-view equivocation.
            voteLocksR(key)
              .set(none)
              .unlessA(ConsensusStorage.retainVoteLockAcrossAbandon(viewChangePolicy)) >>
            assembledVccApplyScheduledR(key).set(none) >>
            assembledVccReceiptsR(key).set(none) >>
            timeoutCertificateApplyScheduledR(key).set(none) >>
            assembledAdmissionCertsR(key).set(none)

        def pruneAttemptDeclarationsForView(key: Key, minViewToKeep: Long): F[Unit] =
          updateResources(key) { resources =>
            resources.copy(
              peerDeclarationsMap = resources.peerDeclarationsMap.view
                .mapValues(ConsensusStorage.pruneAttemptDeclarationsForView(_, minViewToKeep))
                .toMap
            )
          }.void

        def pruneStaleProposalSlots(key: Key, minViewToKeep: Long): F[Unit] =
          updateResources(key) { resources =>
            resources.copy(
              peerDeclarationsMap = resources.peerDeclarationsMap.map {
                case (peerId, decl) =>
                  val updated = decl.proposal match {
                    case Some(p) if p.view < minViewToKeep && p.vcc.isEmpty && p.timeoutCertificate.isEmpty =>
                      decl.focus(_.proposal).replace(none)
                    case _ => decl
                  }
                  peerId -> updated
              }
            )
          }.void

        def tickStaleLocalViewAtSameKey(key: Key): F[Int] =
          staleLocalViewAtSameKeyR.modify {
            case (Some(lastKey), count) if lastKey === key =>
              val newCount = count + 1
              ((key.some, newCount), newCount)
            case _ =>
              ((key.some, 1), 1)
          }

        def getStaleLocalViewAtSameKey(key: Key): F[Int] =
          staleLocalViewAtSameKeyR.get.map {
            case (Some(lastKey), count) if lastKey === key => count
            case _                                         => 0
          }

        def clearStaleLocalViewAtSameKey: F[Unit] =
          staleLocalViewAtSameKeyR.set((none[Key], 0))

        def tickSoftResetAtSameKey(key: Key): F[Int] =
          softResetAtSameKeyR.modify {
            case (Some(lastKey), count) if lastKey === key =>
              val newCount = count + 1
              ((key.some, newCount), newCount)
            case _ =>
              ((key.some, 1), 1)
          }

        def getSoftResetCountAtSameKey(key: Key): F[Int] =
          softResetAtSameKeyR.get.map {
            case (Some(lastKey), count) if lastKey === key => count
            case _                                         => 0
          }

        def getPeerDeclarations(key: Key): F[Map[PeerId, PeerDeclarations]] =
          resourcesR(key).get.map {
            case None            => Map.empty[PeerId, PeerDeclarations]
            case Some(resources) => resources.peerDeclarationsMap
          }

        def softResetRoundState(key: Key): F[Boolean] =
          // A same-key reset is safe only before this attempt has voted or crossed a
          // certified view boundary. Real download/rollback cleanup remains the only
          // operation allowed to release a populated vote lock.
          (
            statesR(key).get,
            voteLocksR(key).get,
            assembledVccApplyScheduledR(key).get,
            timeoutCertificateApplyScheduledR(key).get
          ).tupled.flatMap {
            case (maybeState, voteLock, vccApplyScheduled, timeoutApplyScheduled) =>
              val viewNumber = maybeState.fold(0)(_.viewNumber)
              // An inbound VCC is stored before its quorum/signature validation completes.
              // Only the apply-scheduled markers prove that a certificate crossed validation
              // and now owns the view transition. Treating the raw assembled slot as certified
              // lets a malformed rumor permanently suppress the safe pre-vote reset path.
              val hasCertifiedAdvance = vccApplyScheduled.nonEmpty || timeoutApplyScheduled.nonEmpty
              if (!ConsensusStorage.sameKeySoftResetAllowed(viewNumber, voteLock, hasCertifiedAdvance)) false.pure[F]
              else
                Clock[F].monotonic.flatMap { now =>
                  updateResources(key) { resources =>
                    ConsensusResources[Artifact, Kind](
                      peerDeclarationsMap = resources.peerDeclarationsMap.view
                        .mapValues(ConsensusStorage.retainFacilityOnly)
                        .toMap,
                      acksMap = Map.empty,
                      withdrawalsMap = Map.empty,
                      ackKinds = Set.empty,
                      artifacts = Map.empty,
                      updatedAt = now,
                      viewChangeVotes = Map.empty,
                      proposalQcs = Map.empty,
                      evictionVotes = Map.empty,
                      admissionVotes = Map.empty
                    )
                  }.void >>
                    assembledVccR(key).set(none) >>
                    assembledVccApplyScheduledR(key).set(none) >>
                    assembledVccReceiptsR(key).set(none) >>
                    assembledEvictionCertsR(key).set(none) >>
                    assembledAdmissionCertsR(key).set(none) >>
                    timeoutCertificateApplyScheduledR(key).set(none) >>
                    statesR(key).set(none) >>
                    pendingStateEffectsR(key).set(none) >>
                    // softResetRoundState bypasses condModifyState, so explicitly advance the
                    // attempt epoch. This invalidates any AbandonRound/RoundCompleted decision
                    // sampled by the monitor before the reset.
                    roundAttemptIdR.update(_ + 1L) >>
                    stateAttemptIdR(key).set(none) >>
                    true.pure[F]
                }
          }

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

        def registerPeer(peerId: PeerId, newKey: Key): F[Unit] = {
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
          }
        }

        def getPeerRegistrations: F[Map[PeerId, Key]] = peerRegistrationsR.get

        def getRoundAttemptId: F[Long] = roundAttemptIdR.get

        def getStateAttemptId(key: Key): F[Option[Long]] = stateAttemptIdR(key).get

        def getResourceGeneration(key: Key): F[Long] = resourceGenerationR(key).get.map(_.getOrElse(0L))

        def markPacemakerEmissionProgress(key: Key): F[Unit] =
          resourceGenerationR(key).update(current => Some(current.getOrElse(0L) + 1L))

        def observePeerAtKey(peerId: PeerId, key: Key): F[Unit] =
          peerCurrentKeysR.update { m =>
            m.updatedWith(peerId) {
              case Some(existing) if Order[Key].gteqv(existing, key) => existing.some
              case _                                                 => key.some
            }
          }

        def getPeerCurrentKeys: F[Map[PeerId, Key]] = peerCurrentKeysR.get

        def pruneStaleResources(activeKey: Key): F[Unit] =
          (resourcesR.keys, resourceGenerationR.keys, pendingStateEffectsR.keys, stateAttemptIdR.keys).tupled.flatMap {
            case (resourceKeys, generationKeys, effectKeys, stateAttemptKeys) =>
              // Only prune keys STRICTLY LESS THAN activeKey. Pre-arrived declarations for future
              // rounds (within the `declarationRangeLimit` window) are already admitted by
              // `updateResources` and must survive completion of earlier rounds; wiping them here
              // on every `activeKey` advance erased legitimate pipelined state. Observed in
              // E2E: Facility declarations for round N+1 arriving ~100ms before round N's local
              // finalization were stored in resourcesR(N+1), then deleted when round N completed,
              // leaving the new round N+1 with `progress=3/5 missing=2` forever because the two
              // "missing" peers never retransmitted. StallDetector logged them as missing for 2+
              // minutes despite DECL_RECEIVED entries for all 5 facilities on the same node.
              //
              // The acceptance window in `updateResources` (`[lastOutcome.key, lastOutcome.key +
              // declarationRangeLimit]`) bounds memory growth; preserving future keys here is
              // consistent with that contract.
              resourceKeys.filter(Order[Key].lt(_, activeKey)).traverse_(k => resourcesR(k).set(none)) >>
                generationKeys.filter(Order[Key].lt(_, activeKey)).traverse_(k => resourceGenerationR(k).set(none)) >>
                effectKeys.filter(Order[Key].lt(_, activeKey)).traverse_(k => pendingStateEffectsR(k).set(none)) >>
                stateAttemptKeys.filter(Order[Key].lt(_, activeKey)).traverse_(k => stateAttemptIdR(k).set(none))
          }

        def pruneStalePeerRegistrations(activePeers: Set[PeerId]): F[Unit] =
          peerRegistrationsR.update(_.view.filterKeys(activePeers.contains).toMap) >>
            peerCurrentKeysR.update(_.view.filterKeys(activePeers.contains).toMap)

        def clearAllPeerRegistrations: F[Unit] =
          peerRegistrationsR.set(Map.empty) >> peerCurrentKeysR.set(Map.empty)

        def cleanupConflictedRound(key: Key): F[Unit] =
          // A losing current round must invalidate queued commands, then remove its per-key
          // epoch rather than leaving one MapRef entry per conflict.
          roundAttemptIdR.update(_ + 1L) >>
            statesR(key).set(none) >>
            stateAttemptIdR(key).set(none) >>
            clearResources(key)

        def clearAllConsensusState: F[Unit] =
          for {
            // Recovery/download is the explicit boundary that invalidates every queued
            // monitor decision. Bump before clearing so a pre-recovery AbandonRound can
            // never drain against state initialized afterward with the same epoch.
            _ <- roundAttemptIdR.update(_ + 1L)
            // The accepted outcome is consensus state too. Leaving it installed makes the
            // subsequent InitializeFromDownload fail closed when it tries to install the
            // newer, certified recovery outcome. Both callers of this boundary are explicit
            // recovery/rollback paths; ordinary round cleanup never reaches it.
            _ <- lastOutcomeR.set(none)
            stateKeys <- statesR.keys
            _ <- stateKeys.traverse_(k => statesR(k).set(none))
            stateAttemptKeys <- stateAttemptIdR.keys
            _ <- stateAttemptKeys.traverse_(k => stateAttemptIdR(k).set(none))
            resourceKeys <- resourcesR.keys
            _ <- resourceKeys.traverse_(k => resourcesR(k).set(none))
            resourceGenerationKeys <- resourceGenerationR.keys
            _ <- resourceGenerationKeys.traverse_(k => resourceGenerationR(k).set(none))
            pendingEffectKeys <- pendingStateEffectsR.keys
            _ <- pendingEffectKeys.traverse_(k => pendingStateEffectsR(k).set(none))
            voteLockKeys <- voteLocksR.keys
            _ <- voteLockKeys.traverse_(k => voteLocksR(k).set(none))
            vccKeys <- assembledVccR.keys
            _ <- vccKeys.traverse_(k => assembledVccR(k).set(none))
            vccScheduleKeys <- assembledVccApplyScheduledR.keys
            _ <- vccScheduleKeys.traverse_(k => assembledVccApplyScheduledR(k).set(none))
            vccReceiptKeys <- assembledVccReceiptsR.keys
            _ <- vccReceiptKeys.traverse_(k => assembledVccReceiptsR(k).set(none))
            ecsKeys <- assembledEvictionCertsR.keys
            _ <- ecsKeys.traverse_(k => assembledEvictionCertsR(k).set(none))
            acsKeys <- assembledAdmissionCertsR.keys
            _ <- acsKeys.traverse_(k => assembledAdmissionCertsR(k).set(none))
            tcsKeys <- timeoutCertificateApplyScheduledR.keys
            _ <- tcsKeys.traverse_(k => timeoutCertificateApplyScheduledR(k).set(none))
          } yield ()
      }
  }
}
