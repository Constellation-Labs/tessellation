package io.constellationnetwork.schema

import scala.collection.immutable.SortedMap

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import io.circe.parser.decode
import io.circe.syntax._
import weaver.SimpleIOSuite

object ConsensusOperationalStateSuite extends SimpleIOSuite {

  private def peer(c: Char): PeerId =
    PeerId(Hex(c.toString * 128))

  private val populated: ConsensusOperationalState =
    ConsensusOperationalState(
      peerQuality = SortedMap(peer('a') -> ((5, 7)), peer('b') -> ((9, 9))),
      removalPenalties = SortedMap(peer('a') -> 12, peer('c') -> 3),
      cumulativeMissCounts = SortedMap(peer('a') -> 2L, peer('b') -> 1L),
      readmissionCountdown = SortedMap(peer('c') -> 4),
      recentProofSizes = SortedMap(SnapshotOrdinal.unsafeApply(100L) -> 7, SnapshotOrdinal.unsafeApply(101L) -> 6),
      deferralCountdown = SortedMap(peer('b') -> 2)
    )

  pureTest("empty has all-empty maps") {
    val e = ConsensusOperationalState.empty
    expect.all(
      e.peerQuality.isEmpty,
      e.removalPenalties.isEmpty,
      e.cumulativeMissCounts.isEmpty,
      e.readmissionCountdown.isEmpty,
      e.recentProofSizes.isEmpty,
      e.deferralCountdown.isEmpty
    )
  }

  pureTest("JSON roundtrip preserves all six fields") {
    val encoded = populated.asJson
    val decoded = decode[ConsensusOperationalState](encoded.noSpaces)
    expect(decoded == Right(populated))
  }

  pureTest("JSON encoding is byte-stable across repeats (determinism)") {
    val a = populated.asJson.noSpaces
    val b = populated.asJson.noSpaces
    expect(a == b)
  }

  pureTest("populated state values are reachable as constructed") {
    expect.all(
      populated.peerQuality(peer('a')) == ((5, 7)),
      populated.removalPenalties(peer('a')) == 12,
      populated.cumulativeMissCounts(peer('b')) == 1L,
      populated.readmissionCountdown(peer('c')) == 4,
      populated.recentProofSizes(SnapshotOrdinal.unsafeApply(100L)) == 7,
      populated.deferralCountdown(peer('b')) == 2
    )
  }

  // v20 contract: snapshot.peerHistory is Optional[None] in pre-v20 data and the
  // restore path must read empty without crashing. This guards Main.scala's
  // `getOrElse(ConsensusOperationalState.empty)` consumption pattern.
  pureTest("None peerHistory maps to empty operational state on restore") {
    val none: Option[ConsensusOperationalState] = None
    val restored = none.getOrElse(ConsensusOperationalState.empty)
    expect(restored == ConsensusOperationalState.empty)
  }
}
