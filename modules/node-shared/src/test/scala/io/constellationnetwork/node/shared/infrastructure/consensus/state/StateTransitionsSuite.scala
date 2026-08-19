package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.effect.{IO, Ref}
import cats.syntax.functor._

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import weaver.SimpleIOSuite

object StateTransitionsSuite extends SimpleIOSuite {

  private def pid(name: String): PeerId =
    PeerId(Hex(name.getBytes("UTF-8").map(b => f"$b%02x").mkString))

  test("download validation completes before the first mutation") {
    for {
      events <- Ref.of[IO, List[String]](Nil)
      result <- StateTransitions.validateDownloadBeforeMutation(
        events.update(_ :+ "validated"),
        events.update(_ :+ "mutated").as("installed")
      )
      observed <- events.get
    } yield expect.same("installed", result) && expect.same(List("validated", "mutated"), observed)
  }

  test("failed download validation prevents every mutation") {
    val failure = new IllegalStateException("invalid certified outcome")

    for {
      mutated <- Ref.of[IO, Boolean](false)
      result <- StateTransitions
        .validateDownloadBeforeMutation[IO, Unit](IO.raiseError(failure), mutated.set(true))
        .attempt
      wasMutated <- mutated.get
    } yield expect.same(Left(failure), result) && expect(!wasMutated)
  }

  pureTest("exact downloaded outcomes preserve the caller's recovery mode") {
    val normal = StateTransitions.downloadOutcomeDisposition(
      keyMatches = true,
      artifactMatches = true,
      contextMatches = true,
      isRecovery = false
    )
    val recovery = StateTransitions.downloadOutcomeDisposition(
      keyMatches = true,
      artifactMatches = true,
      contextMatches = true,
      isRecovery = true
    )

    expect.same(StateTransitions.DownloadOutcomeDisposition.AcceptExact(false), normal) &&
    expect.same(StateTransitions.DownloadOutcomeDisposition.AcceptExact(true), recovery)
  }

  pureTest("a newer downloaded outcome requires application-storage alignment") {
    val disposition = StateTransitions.downloadOutcomeDisposition(
      keyMatches = false,
      artifactMatches = false,
      contextMatches = false,
      isRecovery = true
    )

    expect.same(StateTransitions.DownloadOutcomeDisposition.AcceptAndAlignApplicationStorage, disposition)
  }

  pureTest("a same-key artifact or context mismatch is rejected") {
    val artifactMismatch = StateTransitions.downloadOutcomeDisposition(
      keyMatches = true,
      artifactMatches = false,
      contextMatches = true,
      isRecovery = true
    )
    val contextMismatch = StateTransitions.downloadOutcomeDisposition(
      keyMatches = true,
      artifactMatches = true,
      contextMatches = false,
      isRecovery = true
    )

    expect.same(StateTransitions.DownloadOutcomeDisposition.Reject, artifactMismatch) &&
    expect.same(StateTransitions.DownloadOutcomeDisposition.Reject, contextMismatch)
  }

  pureTest("view-change leader pool uses Core when Core is populated") {
    val core = List(pid("core-1"), pid("core-2"))
    val nonCore = pid("non-core")
    val allFacilitators = core :+ nonCore

    val pool = StateTransitions.viewChangeLeaderPool(core, allFacilitators)

    expect.same(core, pool) &&
    expect(!pool.contains(nonCore))
  }

  pureTest("view-change leader pool falls back to facilitators when Core is empty") {
    val facilitators = List(pid("fac-1"), pid("fac-2"))

    expect.same(facilitators, StateTransitions.viewChangeLeaderPool(Nil, facilitators))
  }

  pureTest("ready promotion quorum requires an external corroborator for a two-peer recovery view") {
    expect.same(2, StateTransitions.readyPromotionQuorum(2, 2.0 / 3.0))
  }

  pureTest("ready promotion quorum follows supermajority for larger recovery views") {
    expect.same(4, StateTransitions.readyPromotionQuorum(5, 2.0 / 3.0))
  }

  pureTest("ready promotion external Ready floor accepts a single rollback lead witness") {
    expect.same(1, StateTransitions.readyPromotionExternalReadyFloor)
  }

  pureTest("ready promotion allows the rollback-lead topology with one aligned Ready witness") {
    expect(StateTransitions.readyPromotionAllowed(readyCandidates = 1, externalAligned = 1, required = 2))
  }

  pureTest("ready promotion rejects no Ready witnesses") {
    expect(!StateTransitions.readyPromotionAllowed(readyCandidates = 0, externalAligned = 0, required = 2))
  }

  pureTest("ready promotion allows quorum without unanimity across Ready witnesses") {
    expect(StateTransitions.readyPromotionAllowed(readyCandidates = 3, externalAligned = 2, required = 3))
  }

  pureTest("ready promotion rejects multiple Ready witnesses below quorum") {
    expect(!StateTransitions.readyPromotionAllowed(readyCandidates = 3, externalAligned = 1, required = 3))
  }

  pureTest("ready promotion allows multiple Ready witnesses when all visible Ready peers agree") {
    expect(StateTransitions.readyPromotionAllowed(readyCandidates = 2, externalAligned = 2, required = 2))
  }

  pureTest("rollback first-round status is infeasible before rollback node is Ready") {
    val status = StateTransitions.rollbackFirstRoundQuorumStatus(
      selfReady = false,
      externalReadyPeers = 2,
      activeFacilitatorFloor = 3,
      quorumThresholdFraction = 2.0 / 3.0
    )

    expect.same(2, status.participantsIncludingSelf) &&
    expect.same(2, status.required) &&
    expect(!status.quorumFeasible)
  }

  pureTest("rollback first-round status is feasible once Ready peers meet the active floor") {
    val status = StateTransitions.rollbackFirstRoundQuorumStatus(
      selfReady = true,
      externalReadyPeers = 2,
      activeFacilitatorFloor = 3,
      quorumThresholdFraction = 2.0 / 3.0
    )

    expect.same(3, status.participantsIncludingSelf) &&
    expect.same(2, status.required) &&
    expect(status.quorumFeasible)
  }

  pureTest("rollback first-round status remains infeasible for rollback lead alone under testnet floor") {
    val status = StateTransitions.rollbackFirstRoundQuorumStatus(
      selfReady = true,
      externalReadyPeers = 0,
      activeFacilitatorFloor = 3,
      quorumThresholdFraction = 2.0 / 3.0
    )

    expect.same(1, status.participantsIncludingSelf) &&
    expect.same(1, status.required) &&
    expect(!status.quorumFeasible)
  }

  pureTest("certified same-key recovery accepts multiple proof carriers for one semantic value") {
    val valueHash = Hash.fromBytes("one-value".getBytes("UTF-8"))

    expect.same(
      Right(Some("peer-a")),
      StateTransitions.selectCertifiedRecoveryCandidate(List(valueHash -> "peer-a", valueHash -> "peer-b"))
    )
  }

  pureTest("certified same-key recovery fails closed on two valid semantic values") {
    val first = Hash.fromBytes("first-value".getBytes("UTF-8"))
    val second = Hash.fromBytes("second-value".getBytes("UTF-8"))

    expect.same(
      Left(2),
      StateTransitions.selectCertifiedRecoveryCandidate(List(first -> "peer-a", second -> "peer-b"))
    )
  }

  pureTest("normal first-round alignment counts an exact committee quorum and ignores unrelated Ready peers") {
    val self = pid("normal-lead")
    val peerA = pid("normal-a")
    val peerB = pid("normal-b")
    val peerC = pid("normal-c")
    val absentA = pid("normal-absent-a")
    val absentB = pid("normal-absent-b")
    val unrelated = pid("normal-unrelated")
    val committee = SortedSet(self, peerA, peerB, peerC, absentA, absentB)
    val responsive = Map(
      peerA -> NodeState.Ready,
      peerB -> NodeState.WaitingForReady,
      peerC -> NodeState.Ready,
      unrelated -> NodeState.Ready
    )
    val outcomes = Map(
      peerA -> StateTransitions.RecoveryPlanPeerOutcome.Aligned,
      peerB -> StateTransitions.RecoveryPlanPeerOutcome.Aligned,
      peerC -> StateTransitions.RecoveryPlanPeerOutcome.Aligned,
      unrelated -> StateTransitions.RecoveryPlanPeerOutcome.Aligned
    )
    val status = StateTransitions.firstRoundAlignmentBarrierStatus(
      self,
      committee,
      StateTransitions.FirstRoundAlignmentRequirement.AtLeast(4),
      selfReady = true,
      responsivePeerStates = responsive,
      peerOutcomes = outcomes
    )

    expect(status.aligned) &&
    expect.same(4, status.alignedCount) &&
    expect.same(4, status.required) &&
    expect.same(0, status.deficit) &&
    expect.same(SortedSet(absentA, absentB), status.missingSession) &&
    expect(!status.expectedExternal.contains(unrelated))
  }

  pureTest("normal first-round alignment holds at quorum minus one without treating elapsed attempts as authority") {
    val self = pid("normal-lead")
    val peerA = pid("normal-a")
    val peerB = pid("normal-b")
    val peerC = pid("normal-c")
    val committee = SortedSet(self, peerA, peerB, peerC)
    val status = StateTransitions.firstRoundAlignmentBarrierStatus(
      self,
      committee,
      StateTransitions.FirstRoundAlignmentRequirement.AtLeast(3),
      selfReady = true,
      responsivePeerStates = Map(peerA -> NodeState.Ready, peerB -> NodeState.WaitingForReady, peerC -> NodeState.Ready),
      peerOutcomes = Map(
        peerA -> StateTransitions.RecoveryPlanPeerOutcome.Aligned,
        peerB -> StateTransitions.RecoveryPlanPeerOutcome.Mismatched,
        peerC -> StateTransitions.RecoveryPlanPeerOutcome.FetchFailed
      )
    )

    expect(!status.aligned) &&
    expect.same(2, status.alignedCount) &&
    expect.same(1, status.deficit) &&
    expect(status.mismatchedOutcome.contains(peerB)) &&
    expect(status.fetchFailed.contains(peerC))
  }

  pureTest("normal first-round pulse accepts any exact current-session committee origin and rejects unrelated origins") {
    val self = pid("pulse-self")
    val nonLeadMember = pid("pulse-non-lead")
    val otherMember = pid("pulse-other")
    val unrelated = pid("pulse-unrelated")
    val committee = SortedSet(self, nonLeadMember, otherMember)
    val status = StateTransitions.normalFirstRoundPulseStatus(
      committee,
      matchingFacilityOrigins = Set(nonLeadMember, unrelated),
      aheadProbeOrigins = Set.empty,
      responsivePeerStates = Map(nonLeadMember -> NodeState.Ready, unrelated -> NodeState.Ready),
      peerOutcomes = Map(
        nonLeadMember -> StateTransitions.NormalFirstRoundPulsePeerOutcome.Aligned,
        unrelated -> StateTransitions.NormalFirstRoundPulsePeerOutcome.Aligned
      )
    )

    expect.same(Some(nonLeadMember), status.releaseOrigin) &&
    expect.same(SortedSet(nonLeadMember), status.matchingFacilityOrigins) &&
    expect(!status.matchingFacilityOrigins.contains(unrelated)) &&
    expect.same("aligned", status.outcomeLabel)
  }

  pureTest("normal first-round pulse routes an advanced origin to recovery and never opens on invalid local evidence") {
    val self = pid("pulse-self")
    val ahead = pid("pulse-ahead")
    val aligned = pid("pulse-aligned")
    val mismatched = pid("pulse-mismatched")
    val wrongState = pid("pulse-observing")
    val committee = SortedSet(self, ahead, aligned, mismatched, wrongState)
    val status = StateTransitions.normalFirstRoundPulseStatus(
      committee,
      matchingFacilityOrigins = Set(ahead, aligned, mismatched, wrongState),
      aheadProbeOrigins = Set.empty,
      responsivePeerStates = Map(
        ahead -> NodeState.WaitingForReady,
        aligned -> NodeState.Ready,
        mismatched -> NodeState.Ready,
        wrongState -> NodeState.Observing
      ),
      peerOutcomes = Map(
        ahead -> StateTransitions.NormalFirstRoundPulsePeerOutcome.Ahead,
        aligned -> StateTransitions.NormalFirstRoundPulsePeerOutcome.Aligned,
        mismatched -> StateTransitions.NormalFirstRoundPulsePeerOutcome.Mismatched,
        wrongState -> StateTransitions.NormalFirstRoundPulsePeerOutcome.Aligned
      )
    )

    expect(status.releaseOrigin.isEmpty) &&
    expect.same(Some(ahead), status.aheadOrigin) &&
    expect.same(Some(NodeState.Observing), status.invalidState.get(wrongState)) &&
    expect(status.mismatchedOutcome.contains(mismatched)) &&
    expect.same("peer_ahead", status.outcomeLabel)
  }

  pureTest("normal first-round pulse never reopens an old generation after peer-ahead recovery starts") {
    val self = pid("pulse-self")
    val aligned = pid("pulse-aligned")
    val status = StateTransitions.normalFirstRoundPulseStatus(
      SortedSet(self, aligned),
      matchingFacilityOrigins = Set(aligned),
      aheadProbeOrigins = Set.empty,
      responsivePeerStates = Map(aligned -> NodeState.Ready),
      peerOutcomes = Map(aligned -> StateTransitions.NormalFirstRoundPulsePeerOutcome.Aligned)
    )

    expect(StateTransitions.shouldReleaseNormalFirstRoundPulse(status, recoveryAlreadyTriggered = false)) &&
    expect(!StateTransitions.shouldReleaseNormalFirstRoundPulse(status, recoveryAlreadyTriggered = true))
  }

  pureTest("a future declaration can trigger catch-up but cannot replace the Facility release pulse") {
    val self = pid("pulse-self")
    val future = pid("pulse-future")
    val committee = SortedSet(self, future)
    val alignedAtParent = StateTransitions.normalFirstRoundPulseStatus(
      committee,
      matchingFacilityOrigins = Set.empty,
      aheadProbeOrigins = Set(future),
      responsivePeerStates = Map(future -> NodeState.Ready),
      peerOutcomes = Map(future -> StateTransitions.NormalFirstRoundPulsePeerOutcome.Aligned)
    )
    val committedAhead = StateTransitions.normalFirstRoundPulseStatus(
      committee,
      matchingFacilityOrigins = Set.empty,
      aheadProbeOrigins = Set(future),
      responsivePeerStates = Map(future -> NodeState.Ready),
      peerOutcomes = Map(future -> StateTransitions.NormalFirstRoundPulsePeerOutcome.Ahead)
    )

    expect(alignedAtParent.releaseOrigin.isEmpty) &&
    expect(alignedAtParent.aheadOrigin.isEmpty) &&
    expect.same(Some(future), committedAhead.aheadOrigin) &&
    expect(committedAhead.releaseOrigin.isEmpty)
  }
}
