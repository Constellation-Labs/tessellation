package io.constellationnetwork.node.shared.infrastructure.consensus.state

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.SimpleIOSuite

object StateTransitionsSuite extends SimpleIOSuite {

  private def pid(name: String): PeerId =
    PeerId(Hex(name.getBytes("UTF-8").map(b => f"$b%02x").mkString))

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
}
