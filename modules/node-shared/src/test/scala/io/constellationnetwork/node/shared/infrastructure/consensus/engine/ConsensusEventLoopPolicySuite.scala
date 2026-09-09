package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import io.constellationnetwork.node.shared.infrastructure.consensus.engine.ConsensusEventLoop.InitDownloadFailureDisposition
import io.constellationnetwork.node.shared.infrastructure.consensus.state.StateTransitions
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.FunSuite

object ConsensusEventLoopPolicySuite extends FunSuite {

  private val selfId = PeerId(Hex("01" * 64))

  test("carried probation is an expected Observing deferral rather than a failed download") {
    val error = new StateTransitions.SelfStillInProbation(selfId, "100")
    val disposition = ConsensusEventLoop.initDownloadFailureDisposition(error, hasDirectProbationProbe = true)

    expect.same(InitDownloadFailureDisposition.HoldObservingAndRetry, disposition) &&
    expect(ConsensusEventLoop.shouldRequeueProbationInitialization(disposition, NodeState.Observing)) &&
    expect(!ConsensusEventLoop.shouldRequeueProbationInitialization(disposition, NodeState.WaitingForDownload))
  }

  test("Currency keeps the legacy recovery-download path without a direct probation probe") {
    val error = new StateTransitions.SelfStillInProbation(selfId, "100")
    val disposition = ConsensusEventLoop.initDownloadFailureDisposition(error, hasDirectProbationProbe = false)

    expect.same(InitDownloadFailureDisposition.RestartDownload, disposition) &&
    expect(!ConsensusEventLoop.shouldRequeueProbationInitialization(disposition, NodeState.Observing))
  }

  test("ordinary initialization failures retain the recovery-download path") {
    val disposition = ConsensusEventLoop.initDownloadFailureDisposition(
      new RuntimeException("network failure"),
      hasDirectProbationProbe = true
    )

    expect.same(InitDownloadFailureDisposition.RestartDownload, disposition) &&
    expect(!ConsensusEventLoop.shouldRequeueProbationInitialization(disposition, NodeState.Observing))
  }
}
