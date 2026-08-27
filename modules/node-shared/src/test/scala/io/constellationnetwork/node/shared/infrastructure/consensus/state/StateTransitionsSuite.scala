package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.effect.IO
import cats.effect.kernel.Ref

import scala.collection.immutable.SortedSet

import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.SimpleIOSuite

object StateTransitionsSuite extends SimpleIOSuite {

  private def pid(name: String): PeerId =
    PeerId(Hex(name.getBytes("UTF-8").map(b => f"$b%02x").mkString))

  test("certified advance completes prune and CheckUpdate before fallible observability") {
    Ref.of[IO, List[String]](List.empty).flatMap { order =>
      val append = (value: String) => order.update(_ :+ value)

      for {
        _ <- StateTransitions.completeCertifiedAdvance[IO](
          didAdvance = true,
          prune = append("prune"),
          enqueueCheckUpdate = append("check-update"),
          advancedObservability = append("observability") >> IO.raiseError(new RuntimeException("metrics unavailable")),
          notAdvancedObservability = append("not-advanced")
        )
        observed <- order.get
      } yield expect.same(List("prune", "check-update", "observability"), observed)
    }
  }

  test("certified advance race isolates not-advanced observability failures") {
    Ref.of[IO, List[String]](List.empty).flatMap { order =>
      val append = (value: String) => order.update(_ :+ value)

      for {
        _ <- StateTransitions.completeCertifiedAdvance[IO](
          didAdvance = false,
          prune = append("prune"),
          enqueueCheckUpdate = append("check-update"),
          advancedObservability = append("advanced"),
          notAdvancedObservability = append("not-advanced") >> IO.raiseError(new RuntimeException("metrics unavailable"))
        )
        observed <- order.get
      } yield expect.same(List("not-advanced"), observed)
    }
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

  pureTest("ready promotion retries recovery when exact-or-ahead Ready witnesses meet quorum") {
    expect(
      StateTransitions.readyPromotionRecoveryRetryRequired(
        readyCandidates = 4,
        externalAligned = 1,
        ahead = 3,
        missing = 0,
        mismatched = 0,
        failed = 0,
        required = 4
      )
    )
  }

  pureTest("ready promotion does not retry recovery when exact-or-ahead witnesses are below quorum") {
    expect(
      !StateTransitions.readyPromotionRecoveryRetryRequired(
        readyCandidates = 4,
        externalAligned = 1,
        ahead = 1,
        missing = 2,
        mismatched = 0,
        failed = 0,
        required = 4
      )
    )
  }

  pureTest("ready promotion does not retry recovery across an authenticated mismatch") {
    expect(
      !StateTransitions.readyPromotionRecoveryRetryRequired(
        readyCandidates = 4,
        externalAligned = 1,
        ahead = 2,
        missing = 0,
        mismatched = 1,
        failed = 0,
        required = 4
      )
    )
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

  pureTest("recovery-plan peer outcome requires full typed equality") {
    expect.same(
      StateTransitions.RecoveryPlanPeerOutcome.Aligned,
      StateTransitions.recoveryPlanPeerOutcome("seeded-full-outcome", Some("seeded-full-outcome"))
    ) &&
    expect.same(
      StateTransitions.RecoveryPlanPeerOutcome.Mismatched,
      StateTransitions.recoveryPlanPeerOutcome("seeded-full-outcome", Some("same-key-different-operational-state"))
    ) &&
    expect.same(
      StateTransitions.RecoveryPlanPeerOutcome.Missing,
      StateTransitions.recoveryPlanPeerOutcome[String]("seeded-full-outcome", None)
    )
  }

  pureTest("recovery-plan barrier accepts exactly named Ready and WaitingForReady peers with exact outcomes") {
    val self = pid("recovery-lead")
    val peerA = pid("recovery-a")
    val peerB = pid("recovery-b")
    val unrelated = pid("unrelated-ready")
    val status = StateTransitions.recoveryPlanBarrierStatus(
      selfId = self,
      committee = SortedSet(self, peerA, peerB),
      selfReady = true,
      responsivePeerStates = Map(
        peerA -> NodeState.Ready,
        peerB -> NodeState.WaitingForReady,
        unrelated -> NodeState.Ready
      ),
      peerOutcomes = Map(
        peerA -> StateTransitions.RecoveryPlanPeerOutcome.Aligned,
        peerB -> StateTransitions.RecoveryPlanPeerOutcome.Aligned,
        unrelated -> StateTransitions.RecoveryPlanPeerOutcome.Mismatched
      )
    )

    expect(status.aligned) &&
    expect.same(SortedSet(peerA, peerB), status.expectedExternal) &&
    expect.same(SortedSet(peerA, peerB), status.alignedPeers)
  }

  pureTest("recovery-plan barrier fails closed on missing, wrong-state, mismatched, or unfetched planned peers") {
    val self = pid("recovery-lead")
    val missing = pid("missing")
    val wrongState = pid("observing")
    val mismatched = pid("mismatched")
    val unfetched = pid("unfetched")
    val status = StateTransitions.recoveryPlanBarrierStatus(
      selfId = self,
      committee = SortedSet(self, missing, wrongState, mismatched, unfetched),
      selfReady = true,
      responsivePeerStates = Map(
        wrongState -> NodeState.Observing,
        mismatched -> NodeState.Ready,
        unfetched -> NodeState.WaitingForReady
      ),
      peerOutcomes = Map(mismatched -> StateTransitions.RecoveryPlanPeerOutcome.Mismatched)
    )

    expect(!status.aligned) &&
    expect(status.missingSession.contains(missing)) &&
    expect.same(Some(NodeState.Observing), status.invalidState.get(wrongState)) &&
    expect(status.mismatchedOutcome.contains(mismatched)) &&
    expect(status.fetchFailed.contains(unfetched))
  }

  pureTest("recovery-plan barrier rejects a malformed singleton or a non-Ready lead") {
    val self = pid("recovery-lead")
    val singleton = StateTransitions.recoveryPlanBarrierStatus(
      self,
      SortedSet(self),
      selfReady = true,
      Map.empty,
      Map.empty
    )
    val peerA = pid("recovery-a")
    val nonReadyLead = StateTransitions.recoveryPlanBarrierStatus(
      self,
      SortedSet(self, peerA),
      selfReady = false,
      Map(peerA -> NodeState.Ready),
      Map(peerA -> StateTransitions.RecoveryPlanPeerOutcome.Aligned)
    )

    expect(singleton.invalidCommittee) && expect(!singleton.aligned) && expect(!nonReadyLead.aligned)
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
