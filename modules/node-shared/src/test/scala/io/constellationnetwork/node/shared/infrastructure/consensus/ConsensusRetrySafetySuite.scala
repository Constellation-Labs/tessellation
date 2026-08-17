package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration._
import io.constellationnetwork.node.shared.infrastructure.consensus.engine._
import io.constellationnetwork.node.shared.infrastructure.consensus.state.Candidates
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.EventTrigger
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.signature.Signature

import eu.timepit.refined.types.numeric.NonNegLong
import weaver.FunSuite

object ConsensusRetrySafetySuite extends FunSuite {

  private val facilitatorsHash = Hash.fromBytes("facilitators".getBytes("UTF-8"))
  private val lastSnapshotHash = Hash.fromBytes("parent".getBytes("UTF-8"))
  private val proposalHash = Hash.fromBytes("proposal".getBytes("UTF-8"))
  private val otherHash = Hash.fromBytes("other".getBytes("UTF-8"))
  private val signature = Signature(Hex("00"))
  private val peer = PeerId(Hex("01" * 64))

  private val emptyResources = ConsensusResources[Unit, String](
    peerDeclarationsMap = Map.empty,
    acksMap = Map.empty,
    withdrawalsMap = Map.empty,
    ackKinds = Set.empty,
    artifacts = Map.empty,
    updatedAt = 0.seconds
  )

  private val facility = Facility(
    eventHashes = Set.empty,
    candidates = Candidates(Set.empty),
    trigger = EventTrigger.some,
    facilitatorsHash = facilitatorsHash,
    lastGlobalSnapshotOrdinal = SnapshotOrdinal(NonNegLong(1L)),
    lastSnapshotHash = lastSnapshotHash
  )

  private def proposal(view: Long): Proposal =
    Proposal(
      hash = proposalHash,
      facilitatorsHash = facilitatorsHash,
      lastSnapshotHash = lastSnapshotHash,
      view = view,
      vcc = None
    )

  private def majority(view: Long, hash: Hash = proposalHash): MajoritySignature =
    MajoritySignature(signature, facilitatorsHash, lastSnapshotHash, view, hash)

  test("GL0 same-key retry retains Facility and clears every attempt-bound declaration slot") {
    val declarations = PeerDeclarations(
      facility = facility.some,
      proposal = proposal(0L).some,
      signature = majority(0L).some,
      binarySignature = BinarySignature(signature, facilitatorsHash, lastSnapshotHash).some
    )
    val retained = ConsensusStorage.declarationsAfterAbandon(declarations, LegacyViewChangePolicy.FreezeAfterVote)

    expect(retained.facility.contains(facility))
      .and(expect(retained.proposal.isEmpty))
      .and(expect(retained.signature.isEmpty))
      .and(expect(retained.binarySignature.isEmpty))
  }

  test("Currency same-key retry retains the rc.7 declaration map") {
    val declarations = PeerDeclarations(
      facility = facility.some,
      proposal = proposal(0L).some,
      signature = majority(0L).some,
      binarySignature = BinarySignature(signature, facilitatorsHash, lastSnapshotHash).some
    )

    expect.same(
      declarations,
      ConsensusStorage.declarationsAfterAbandon(declarations, LegacyViewChangePolicy.PreserveLegacy)
    )
  }

  test("a failed CheckUpdate retry never adopts a newer round's attempt token") {
    val oldAttempt = 7L
    val newerAttempt = 8L

    expect
      .same(
        none[Long],
        ConsensusEventLoop.checkUpdateRetryAttempt(
          currentAttemptId = newerAttempt,
          stateAttemptId = oldAttempt.some,
          statePresent = true,
          retainedAttemptId = oldAttempt.some
        )
      )
      .and(
        expect.same(
          none[Long],
          ConsensusEventLoop.checkUpdateRetryAttempt(
            currentAttemptId = newerAttempt,
            stateAttemptId = oldAttempt.some,
            statePresent = true,
            retainedAttemptId = none
          )
        )
      )
  }

  test("a CheckUpdate retry keeps the exact current state attempt") {
    val attempt = 7L

    expect
      .same(
        attempt.some,
        ConsensusEventLoop.checkUpdateRetryAttempt(
          currentAttemptId = attempt,
          stateAttemptId = attempt.some,
          statePresent = true,
          retainedAttemptId = none
        )
      )
      .and(
        expect.same(
          attempt.some,
          ConsensusEventLoop.checkUpdateRetryAttempt(
            currentAttemptId = attempt,
            stateAttemptId = attempt.some,
            statePresent = true,
            retainedAttemptId = attempt.some
          )
        )
      )
  }

  test("certified-view abandon guard matches the exact parent and view transition only") {
    val parent = Hash.fromBytes("certified-parent".getBytes("UTF-8"))
    val otherParent = Hash.fromBytes("other-parent".getBytes("UTF-8"))
    val scheduled = Some(Set((parent, 2L, 3L)))

    expect(ConsensusStorage.isExactTransitionScheduled(scheduled, parent, 2L, 3L))
      .and(expect(!ConsensusStorage.isExactTransitionScheduled(scheduled, parent, 1L, 2L)))
      .and(expect(!ConsensusStorage.isExactTransitionScheduled(scheduled, parent, 3L, 4L)))
      .and(expect(!ConsensusStorage.isExactTransitionScheduled(scheduled, otherParent, 2L, 3L)))
      .and(expect(!ConsensusStorage.isExactTransitionScheduled(None, parent, 2L, 3L)))
  }

  test("abandon retains GL0's fail-closed lock but clears Currency's legacy retry lock") {
    expect(ConsensusStorage.retainVoteLockAcrossAbandon(LegacyViewChangePolicy.FreezeAfterVote))
      .and(expect(!ConsensusStorage.retainVoteLockAcrossAbandon(LegacyViewChangePolicy.PreserveLegacy)))
  }

  test("certified view pruning drops lower-view slots and all view-less binary signatures") {
    val lower = PeerDeclarations(
      facility.some,
      proposal(1L).some,
      majority(1L).some,
      BinarySignature(signature, facilitatorsHash, lastSnapshotHash).some
    )
    val current = PeerDeclarations(facility.some, proposal(2L).some, majority(2L).some, None)
    val prunedLower = ConsensusStorage.pruneAttemptDeclarationsForView(lower, 2L)
    val prunedCurrent = ConsensusStorage.pruneAttemptDeclarationsForView(current, 2L)

    expect(prunedLower.facility.nonEmpty)
      .and(expect(prunedLower.proposal.isEmpty))
      .and(expect(prunedLower.signature.isEmpty))
      .and(expect(prunedLower.binarySignature.isEmpty))
      .and(expect(prunedCurrent.proposal.nonEmpty))
      .and(expect(prunedCurrent.signature.nonEmpty))
  }

  test("MajoritySignature attempt domain rejects every stale domain component") {
    val domain = SignatureAttemptDomain(facilitatorsHash, lastSnapshotHash, view = 2L, proposalHash)
    val valid = majority(2L)
    val wrongView = valid.copy(view = 1L)
    val wrongProposal = valid.copy(proposalHash = otherHash)
    val wrongFacilitators = valid.copy(facilitatorsHash = otherHash)
    val wrongParent = valid.copy(lastSnapshotHash = otherHash)

    expect(domain.contains(valid))
      .and(expect(!domain.contains(wrongView)))
      .and(expect(!domain.contains(wrongProposal)))
      .and(expect(!domain.contains(wrongFacilitators)))
      .and(expect(!domain.contains(wrongParent)))
  }

  test("queued abandon becomes unsafe when a vote lands before command drain") {
    val safeWhenQueued = StallDetector.sameKeyRestartUnsafe(
      viewNumber = 0,
      phaseIndex = 0,
      voteLockPopulated = false,
      policy = LegacyViewChangePolicy.FreezeAfterVote
    )
    val unsafeAtDrain = StallDetector.sameKeyRestartUnsafe(
      viewNumber = 0,
      phaseIndex = 0,
      voteLockPopulated = true,
      policy = LegacyViewChangePolicy.FreezeAfterVote
    )
    val unsafeAfterProposalAcceptance = StallDetector.sameKeyRestartUnsafe(
      viewNumber = 0,
      phaseIndex = 2,
      voteLockPopulated = false,
      policy = LegacyViewChangePolicy.FreezeAfterVote
    )

    expect(!safeWhenQueued)
      .and(expect(unsafeAtDrain))
      .and(expect(unsafeAfterProposalAcceptance))
  }

  test("a locked attempt can only leave through corroborated lagging recovery") {
    val confirmed = AbandonmentTracker.PeersAheadProbe(
      confirmedAhead = true,
      probedPeers = 3,
      respondedPeers = 3,
      corroboratingPeers = 2,
      outcome = AbandonmentTracker.ProbeOutcome.Completed
    )
    val unconfirmed = confirmed.copy(confirmedAhead = false, corroboratingPeers = 1)

    expect.same(
      AbandonmentTracker.LockedAttemptAction.RecoverByDownload,
      AbandonmentTracker.lockedAttemptAction(AbandonReason.Lagging(2, 3, 3), confirmed)
    ) &&
    expect.same(
      AbandonmentTracker.LockedAttemptAction.Retain,
      AbandonmentTracker.lockedAttemptAction(AbandonReason.Lagging(2, 3, 3), unconfirmed)
    ) &&
    expect.same(
      AbandonmentTracker.LockedAttemptAction.Retain,
      AbandonmentTracker.lockedAttemptAction(AbandonReason.RoundTimeout(60L, None), confirmed)
    )
  }

  test("Currency preserves rc.7 higher-view voting and same-key retry policy") {
    val priorVote = VoteLock(highestVotedView = 0L.some, votedHashAtHighestView = proposalHash.some, lockedQc = None)
    val higherViewVote = priorVote.acceptVote(
      view = 1L,
      proposalHash = otherHash,
      effectiveLockedQc = None,
      policy = LegacyViewChangePolicy.PreserveLegacy
    )

    expect(higherViewVote.isRight)
      .and(
        expect(
          !StallDetector.sameKeyRestartUnsafe(
            viewNumber = 0,
            phaseIndex = 2,
            voteLockPopulated = true,
            policy = LegacyViewChangePolicy.PreserveLegacy
          )
        )
      )
      .and(
        expect(
          !StallDetector.sameKeyRestartUnsafe(
            viewNumber = 0,
            phaseIndex = 3,
            voteLockPopulated = true,
            policy = LegacyViewChangePolicy.PreserveLegacy
          )
        )
      )
  }

  test("queued abandon is stale when either state or declarations advance before command drain") {
    expect(AbandonmentTracker.isCurrentDecision(7L, 11L, 7L, 11L))
      .and(expect(!AbandonmentTracker.isCurrentDecision(7L, 11L, 8L, 11L)))
      .and(expect(!AbandonmentTracker.isCurrentDecision(7L, 11L, 7L, 12L)))
  }

  test("same-key soft reset is allowed only before vote, certified view, or later view") {
    val voted = VoteLock(highestVotedView = 0L.some, votedHashAtHighestView = proposalHash.some, lockedQc = None)
    val qcOnly = VoteLock.empty.copy(
      lockedQc = ProposalQC(
        view = 0L,
        proposalHash = proposalHash,
        facilitatorsHash = facilitatorsHash,
        signatures = cats.data.NonEmptySet.of(
          io.constellationnetwork.security.signature.signature.SignatureProof(
            io.constellationnetwork.schema.ID.Id(Hex("01")),
            signature
          )
        )
      ).some
    )

    expect(ConsensusStorage.sameKeySoftResetAllowed(0, None, hasCertifiedAdvance = false))
      .and(expect(!ConsensusStorage.sameKeySoftResetAllowed(0, voted.some, hasCertifiedAdvance = false)))
      .and(expect(!ConsensusStorage.sameKeySoftResetAllowed(0, qcOnly.some, hasCertifiedAdvance = false)))
      .and(expect(!ConsensusStorage.sameKeySoftResetAllowed(1, None, hasCertifiedAdvance = false)))
      .and(expect(!ConsensusStorage.sameKeySoftResetAllowed(0, None, hasCertifiedAdvance = true)))
  }

  test("queued view-change requests are bound to view, state attempt, progress evidence, and unfinished state") {
    val current = ViewChangeManager.requestStillCurrent(2L, 7L, 11L, 2L, 7L, 11L, outcomeReady = false)
    val advancedPhase = ViewChangeManager.requestStillCurrent(2L, 7L, 11L, 2L, 8L, 11L, outcomeReady = false)
    val newEvidence = ViewChangeManager.requestStillCurrent(2L, 7L, 11L, 2L, 7L, 12L, outcomeReady = false)
    val newView = ViewChangeManager.requestStillCurrent(2L, 7L, 11L, 3L, 7L, 11L, outcomeReady = false)
    val finished = ViewChangeManager.requestStillCurrent(2L, 7L, 11L, 2L, 7L, 11L, outcomeReady = true)

    expect(current)
      .and(expect(!advancedPhase))
      .and(expect(!newEvidence))
      .and(expect(!newView))
      .and(expect(!finished))
  }

  test("a stale monitor decision cannot be rebound to a progressed command-loop attempt") {
    val observed = ViewChangeManager.ObservedEpoch(view = 2L, attemptId = 7L, progressGeneration = 11L)

    val whileCurrent = ViewChangeManager.requestForObservation(
      key = 42L,
      observed = observed,
      reason = TimeoutReason.NoProgress,
      currentView = 2L,
      currentAttemptId = 7L,
      currentProgressGeneration = 11L,
      outcomeReady = false
    )
    val afterCommandLoopProgress = ViewChangeManager.requestForObservation(
      key = 42L,
      observed = observed,
      reason = TimeoutReason.NoProgress,
      currentView = 2L,
      currentAttemptId = 8L,
      currentProgressGeneration = 12L,
      outcomeReady = false
    )

    expect(whileCurrent.exists {
      case ConsensusCommand.RequestViewChange(42L, 2L, 7L, 11L, TimeoutReason.NoProgress) => true
      case _                                                                              => false
    }).and(expect(afterCommandLoopProgress.isEmpty))
  }

  test("view-change request coalescing is attempt- and timeout-reason-aware") {
    val noProgress = ViewChangeManager.RequestId(1L, view = 0L, attemptId = 7L, TimeoutReason.NoProgress)
    val infeasible = noProgress.copy(reason = TimeoutReason.QuorumInfeasible)
    val nextAttempt = noProgress.copy(attemptId = 8L)
    val otherKey = noProgress.copy(key = 2L)

    val (one, firstAccepted) =
      ViewChangeManager.registerRequest(Set.empty[ViewChangeManager.RequestId[Long]], noProgress)
    val (duplicate, duplicateAccepted) = ViewChangeManager.registerRequest(one, noProgress)
    val (twoReasons, secondReasonAccepted) = ViewChangeManager.registerRequest(duplicate, infeasible)
    val (next, nextAttemptAccepted) = ViewChangeManager.registerRequest(twoReasons, nextAttempt)
    val (afterLateOldRequest, lateOldRequestAccepted) = ViewChangeManager.registerRequest(next, infeasible)

    expect(firstAccepted)
      .and(expect(!duplicateAccepted))
      .and(expect(secondReasonAccepted))
      .and(expect(twoReasons == Set(noProgress, infeasible)))
      .and(expect(nextAttemptAccepted))
      .and(expect(next == Set(nextAttempt)))
      .and(expect(!lateOldRequestAccepted))
      .and(expect(afterLateOldRequest == next))
      .and(expect(ViewChangeManager.releaseKey(next, 1L).isEmpty))
      .and(expect(ViewChangeManager.releaseKey(next + otherKey, 1L) == Set(otherKey)))
  }

  test("attempt-progress epoch excludes auxiliary votes and timestamps") {
    val auxiliaryOnly = emptyResources.copy(
      viewChangeVotes = Map((0L, 1L) -> Map.empty),
      timeoutVotes = Map((0L, 1L) -> Map.empty),
      timeoutCertificates = Map.empty,
      proposalQcs = Map.empty,
      evictionVotes = Map(peer -> Map.empty),
      admissionVotes = Map(peer -> Map.empty),
      updatedAt = 1.second
    )

    expect(!ConsensusStorage.attemptProgressChanged(emptyResources, auxiliaryOnly))
      .and(expect(!ConsensusStorage.attemptProgressChanged(emptyResources, emptyResources.copy(updatedAt = 2.seconds))))
  }

  test("attempt-progress epoch covers every phase/finality resource") {
    val facilityProgress = emptyResources.copy(peerDeclarationsMap = Map(peer -> PeerDeclarations.empty.copy(facility = facility.some)))
    val proposalProgress = emptyResources.copy(peerDeclarationsMap = Map(peer -> PeerDeclarations.empty.copy(proposal = proposal(0L).some)))
    val majorityProgress =
      emptyResources.copy(peerDeclarationsMap = Map(peer -> PeerDeclarations.empty.copy(signature = majority(0L).some)))
    val binaryProgress = emptyResources.copy(
      peerDeclarationsMap = Map(
        peer -> PeerDeclarations.empty.copy(binarySignature = BinarySignature(signature, facilitatorsHash, lastSnapshotHash).some)
      )
    )
    val ackProgress = emptyResources.copy(acksMap = Map((peer, "facility") -> Set(peer)))
    val withdrawalProgress = emptyResources.copy(withdrawalsMap = Map(peer -> "facility"))
    val ackKindProgress = emptyResources.copy(ackKinds = Set("facility"))
    val artifactProgress = emptyResources.copy(artifacts = Map(proposalHash -> ()))

    expect(ConsensusStorage.attemptProgressChanged(emptyResources, facilityProgress))
      .and(expect(ConsensusStorage.attemptProgressChanged(emptyResources, proposalProgress)))
      .and(expect(ConsensusStorage.attemptProgressChanged(emptyResources, majorityProgress)))
      .and(expect(ConsensusStorage.attemptProgressChanged(emptyResources, binaryProgress)))
      .and(expect(ConsensusStorage.attemptProgressChanged(emptyResources, ackProgress)))
      .and(expect(ConsensusStorage.attemptProgressChanged(emptyResources, withdrawalProgress)))
      .and(expect(ConsensusStorage.attemptProgressChanged(emptyResources, ackKindProgress)))
      .and(expect(ConsensusStorage.attemptProgressChanged(emptyResources, artifactProgress)))
  }

}
