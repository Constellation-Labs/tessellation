package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.FunSuite

object StallDetectorAdmissionVoteTargetsSuite extends FunSuite {

  private def peer(index: Int): PeerId = PeerId(Hex(f"$index%0128x"))

  test("direct probing emits probation and the full open budget independently") {
    val probation = peer(1)
    val openA = peer(2)
    val openB = peer(3)

    val targets = StallDetector.admissionVoteTargets(
      probationReady = List(probation),
      openReady = List(openA, openB),
      maxOpenAdmissions = 1,
      laneProbesEnabled = true
    )

    expect.same(List(probation, openA), targets)
  }

  test("probation observation does not change the selected open targets") {
    val probation = peer(1)
    val openA = peer(2)
    val openB = peer(3)

    val withProbation = StallDetector.admissionVoteTargets(
      probationReady = List(probation),
      openReady = List(openA, openB),
      maxOpenAdmissions = 1,
      laneProbesEnabled = true
    )
    val withoutProbation = StallDetector.admissionVoteTargets(
      probationReady = List.empty,
      openReady = List(openA, openB),
      maxOpenAdmissions = 1,
      laneProbesEnabled = true
    )

    expect.same(withoutProbation, withProbation.filterNot(_ == probation)) &&
    expect.same(List(openA), withoutProbation)
  }

  test("Currency no-probe path preserves rc6 uncapped probation emission") {
    val probationA = peer(1)
    val probationB = peer(2)
    val open = peer(3)

    val targets = StallDetector.admissionVoteTargets(
      probationReady = List(probationA, probationB),
      openReady = List(open),
      maxOpenAdmissions = 0,
      laneProbesEnabled = false
    )

    expect.same(List(probationA, probationB, open), targets)
  }
}
