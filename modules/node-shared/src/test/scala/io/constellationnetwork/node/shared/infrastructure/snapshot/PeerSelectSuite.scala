package io.constellationnetwork.node.shared.infrastructure.snapshot

import io.constellationnetwork.schema.SnapshotOrdinal

import weaver.FunSuite

/** Unit tests for the recovery forward source-corroboration gate (`PeerSelect.corroboratedAheadPool`).
  *
  * The gate must only let a recovering node follow a higher chain when a STRICT MAJORITY of responders corroborate the SAME ahead ordinal;
  * otherwise it must fail closed (return all responders unchanged) so the node never converges onto an uncorroborated minority higher tip.
  * (`String` stands in for the peer.)
  */
object PeerSelectSuite extends FunSuite {

  private def ord(n: Long): SnapshotOrdinal = SnapshotOrdinal.unsafeApply(n)
  private def at(peer: String, n: Long): (String, SnapshotOrdinal) = peer -> ord(n)

  test("a sub-quorum minority ahead (2 of 5, the 2-of-5 fork) fails closed to all responders") {
    val responders = List(at("a", 121L), at("b", 121L), at("c", 121L), at("d", 203L), at("e", 203L))
    val result = PeerSelect.corroboratedAheadPool(responders, Some(ord(121L)))
    expect(result == responders, s"2/5 ahead must fail closed (return all 5), got $result")
  }

  test("a strict majority at one ahead ordinal (3 of 5) gates forward to exactly that pool") {
    val responders = List(at("a", 105L), at("b", 105L), at("c", 105L), at("d", 100L), at("e", 100L))
    val result = PeerSelect.corroboratedAheadPool(responders, Some(ord(100L)))
    val expected = Set(at("a", 105L), at("b", 105L), at("c", 105L))
    expect(result.toSet == expected, s"3/5 at ord 105 must gate forward to the 3 ahead peers, got $result")
  }

  test("an ahead majority split across distinct ordinals (no single-ordinal majority) fails closed") {
    val responders = List(at("a", 101L), at("b", 102L), at("c", 103L), at("d", 100L), at("e", 100L))
    val result = PeerSelect.corroboratedAheadPool(responders, Some(ord(100L)))
    expect(result == responders, s"3/5 ahead but split across ordinals must fail closed, got $result")
  }

  test("no local ordinal (normal non-recovery select) is identity") {
    val responders = List(at("a", 105L), at("b", 100L))
    expect(
      PeerSelect.corroboratedAheadPool(responders, None) == responders,
      "None must be inert (prior behavior) for the normal select path"
    )
  }

  test("no responder strictly ahead (rollback / already caught up) is identity") {
    val responders = List(at("a", 100L), at("b", 100L), at("c", 90L))
    expect(
      PeerSelect.corroboratedAheadPool(responders, Some(ord(100L))) == responders,
      "with no ahead responder the gate must be inert so rollback recovery is unaffected"
    )
  }
}
