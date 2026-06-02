package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.node.shared.infrastructure.consensus.ActiveFacilitatorAdmission.ExclusionReason
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.SimpleIOSuite

object ActiveFacilitatorAdmissionSuite extends SimpleIOSuite {

  private def peer(c: Char): PeerId = PeerId(Hex(c.toString * 128))
  private def ord(n: Long): SnapshotOrdinal = SnapshotOrdinal.unsafeApply(n)

  private val a = peer('a')
  private val b = peer('b')
  private val c = peer('c')
  private val d = peer('d')

  private def window(entries: (Long, Set[PeerId])*): SortedMap[SnapshotOrdinal, SortedSet[PeerId]] =
    SortedMap.from(entries.toList.map { case (o, ps) => ord(o) -> SortedSet.from(ps) })

  pureTest("does not filter when recent signer window is not deep enough") {
    val result = ActiveFacilitatorAdmission.fromRecentSigners(
      selected = List(a, b, c),
      recentSigners = window(1L -> Set(a, b)),
      minActiveSize = 2
    )

    expect.same(List(a, b, c), result.active) &&
    expect(result.exclusions.isEmpty) &&
    expect(!result.recentFilterApplied)
  }

  pureTest("excludes only peers absent from every recent signer set") {
    val result = ActiveFacilitatorAdmission.fromRecentSigners(
      selected = List(a, b, c, d),
      recentSigners = window(
        10L -> Set(a, b),
        11L -> Set(a, c),
        12L -> Set(a, b)
      ),
      minActiveSize = 2
    )

    expect.same(List(a, b, c), result.active) &&
    expect.same(Set(d), result.exclusions.collect { case e if e.reason == ExclusionReason.NotRecentSigner => e.peerId }.toSet) &&
    expect(result.recentFilterApplied)
  }

  pureTest("bypasses filter when recent signer pool would fall below active floor") {
    val result = ActiveFacilitatorAdmission.fromRecentSigners(
      selected = List(a, b, c),
      recentSigners = window(
        10L -> Set(a),
        11L -> Set(a),
        12L -> Set(a)
      ),
      minActiveSize = 2
    )

    expect.same(List(a, b, c), result.active) &&
    expect(result.exclusions.isEmpty) &&
    expect(!result.recentFilterApplied)
  }

  pureTest("preserves selected facilitator order after filtering") {
    val result = ActiveFacilitatorAdmission.fromRecentSigners(
      selected = List(c, a, d, b),
      recentSigners = window(
        10L -> Set(a, b, c),
        11L -> Set(a, b, c),
        12L -> Set(a, b, c)
      ),
      minActiveSize = 2
    )

    expect.same(List(c, a, b), result.active)
  }
}
