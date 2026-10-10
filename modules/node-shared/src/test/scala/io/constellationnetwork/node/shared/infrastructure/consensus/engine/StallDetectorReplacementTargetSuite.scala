package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.FunSuite

object StallDetectorReplacementTargetSuite extends FunSuite {

  private def peer(index: Int): PeerId = PeerId(Hex(f"$index%0128x"))

  private val committee = Set(peer(1), peer(2), peer(3), peer(4))
  private val target = peer(4)

  test("replacement target and probation nominee cannot enter the paired open lane") {
    val candidate = peer(5)
    val candidates = StallDetector.excludeAtomicReplacementTargets(Set(target, candidate), List(target))
    val selected = StallDetector.openAdmissionTargets(
      candidates = candidates,
      committee = committee,
      probation = Set(candidate),
      alreadyVotedBySelf = Set.empty,
      rotationIndex = 1L,
      maxOpenAdmissions = 1,
      selfIsMember = true
    )

    expect(!candidates.contains(target)) && expect(selected.isEmpty)
  }
}
