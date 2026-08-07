package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{ControllerEvidenceEntry, SnapshotOrdinal}
import io.constellationnetwork.security.hex.Hex

import weaver.SimpleIOSuite

object LegacyRewardQualifiedFacilitatorsSuite extends SimpleIOSuite {

  private def peer(c: Char): PeerId = PeerId(Hex(c.toString * 128))
  private def ord(n: Long): SnapshotOrdinal = SnapshotOrdinal.unsafeApply(n)

  private def entry(roundStart: Set[PeerId], completed: Set[PeerId]): ControllerEvidenceEntry =
    ControllerEvidenceEntry(
      roundStartFacilitators = SortedSet.from(roundStart),
      completedSigners = SortedSet.from(completed),
      timeoutVoters = SortedSet.empty,
      admittedPeers = SortedSet.empty,
      evictedPeers = SortedSet.empty
    )

  pureTest("legacy replay rule retains the deployed evidence-score filtering") {
    val a = peer('a')
    val b = peer('b')
    val evidence = SortedMap.from(
      (1L to 5L).map { n =>
        ord(n) -> entry(Set(a, b), if (n <= 2L) Set(a, b) else Set(a))
      }
    )

    val result = ControllerEvidenceDerivation.legacyRewardQualifiedFacilitators(
      SortedSet(a, b),
      Some(evidence),
      promoteThreshold = 100
    )

    expect.same(SortedSet(a), result)
  }

  pureTest("legacy replay rule falls back to all facilitators without evidence") {
    val facilitators = SortedSet(peer('a'), peer('b'))

    val result = ControllerEvidenceDerivation.legacyRewardQualifiedFacilitators(facilitators, None, promoteThreshold = 100)

    expect.same(facilitators, result)
  }
}
