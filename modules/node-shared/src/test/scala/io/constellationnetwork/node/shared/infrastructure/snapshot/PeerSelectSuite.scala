package io.constellationnetwork.node.shared.infrastructure.snapshot

import io.constellationnetwork.schema.SnapshotOrdinal

import weaver.FunSuite

/** Unit tests for the recovery forward source-corroboration heuristic (`PeerSelect.corroboratedAheadPool`) and the `peersAtOrAbove` probe
  * filter.
  *
  * This is a LIVENESS / efficiency heuristic, not a fork-safety boundary (fork-safety on recovery is enforced on download: snapshot
  * signature validation against the seedlist plus the optional seedlist-signed recovery checkpoint). The heuristic keeps the responders at
  * the single most-common ahead ordinal only when that ordinal has a strict majority -- biasing sourcing away from a lone raced-ahead peer
  * -- otherwise returns all responders unchanged. (`String` stands in for the peer.)
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

  test("peersAtOrAbove keeps peers at or above the ordinal and drops those below") {
    val responded = List(at("a", 255L), at("b", 255L), at("c", 199L), at("d", 154L))
    val result = PeerSelect.peersAtOrAbove(responded, ord(255L))
    expect(result.toSet == Set("a", "b"), s"only peers with tip >= 255 can serve ord 255, got $result")
  }

  test("peersAtOrAbove is inclusive at the boundary ordinal") {
    val responded = List(at("a", 100L), at("b", 101L), at("c", 99L))
    val result = PeerSelect.peersAtOrAbove(responded, ord(100L))
    expect(result.toSet == Set("a", "b"), s"a tip == the target ordinal must be kept, got $result")
  }

  test("peersAtOrAbove drops a behind stale-Ready source so the hash probe cannot wedge on its 503") {
    // Mutual-503 scenario: 2 Ready peers at the tip (255) plus 2 recovering peers behind (199, 154) that would
    // 503 the /255/hash probe. The corroboration probe must target only the at-tip peers.
    val responded = List(at("ready1", 255L), at("ready2", 255L), at("recovering1", 199L), at("recovering2", 154L))
    val result = PeerSelect.peersAtOrAbove(responded, ord(255L))
    expect(result.toSet == Set("ready1", "ready2"), s"must drop the behind recovering peers, got $result")
  }
}
