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

  private def fromRecent(
    selected: List[PeerId],
    recentSigners: SortedMap[SnapshotOrdinal, SortedSet[PeerId]],
    peerQuality: Map[PeerId, (Int, Int)] = Map.empty,
    minActiveSize: Int = 2,
    targetActiveSize: Int = 3,
    maxActiveSize: Int = 4,
    minParticipationObservations: Int = 3,
    minParticipationRatio: Double = 0.5
  ): ActiveFacilitatorAdmission.Result =
    ActiveFacilitatorAdmission.fromRecentSigners(
      selected = selected,
      recentSigners = recentSigners,
      peerQuality = peerQuality,
      minActiveSize = minActiveSize,
      targetActiveSize = targetActiveSize,
      maxActiveSize = maxActiveSize,
      minParticipationObservations = minParticipationObservations,
      minParticipationRatio = minParticipationRatio
    )

  pureTest("does not filter when recent signer window is not deep enough") {
    val result = fromRecent(
      selected = List(a, b, c),
      recentSigners = window(1L -> Set(a, b)),
      minActiveSize = 2
    )

    expect.same(List(a, b, c), result.active) &&
    expect(result.exclusions.isEmpty) &&
    expect(!result.recentFilterApplied)
  }

  pureTest("expands beyond recent signers with quality-ranked candidates") {
    val result = fromRecent(
      selected = List(a, b, c, d),
      recentSigners = window(
        10L -> Set(a, b),
        11L -> Set(a, b),
        12L -> Set(a, b)
      ),
      peerQuality = Map(c -> (5, 5), d -> (1, 5)),
      targetActiveSize = 3
    )

    expect.same(List(a, b, c), result.active) &&
    expect.same(Set(d), result.exclusions.collect { case e if e.reason == ExclusionReason.QualityBelowThreshold => e.peerId }.toSet) &&
    expect.same(1, result.expansionAdmittedSize) &&
    expect(result.recentFilterApplied)
  }

  pureTest("bypasses filter when recent signer pool would fall below active floor") {
    val result = fromRecent(
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
    val result = fromRecent(
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

  pureTest("target is not a cap for recent signers") {
    val result = fromRecent(
      selected = List(a, b, c, d),
      recentSigners = window(
        10L -> Set(a, b, c, d),
        11L -> Set(a, b, c, d),
        12L -> Set(a, b, c, d)
      ),
      targetActiveSize = 3,
      maxActiveSize = 4
    )

    expect.same(List(a, b, c, d), result.active) &&
    expect.same(4, result.targetSize)
  }

  pureTest("certified timeout shrink retains timeout voters when floor is satisfied") {
    val result = ActiveFacilitatorAdmission.fromCertifiedTimeout(
      selected = List(a, b, c),
      recentSigners = window(
        10L -> Set(a, b, c),
        11L -> Set(a, b, c),
        12L -> Set(a, b, c)
      ),
      timeoutVoters = Set(a, c),
      minActiveSize = 2
    )

    expect.same(List(a, c), result.active) &&
    expect.same(Set(b), result.exclusions.collect { case e if e.reason == ExclusionReason.CertifiedTimeoutMissing => e.peerId }.toSet) &&
    expect(result.recentFilterApplied)
  }

  pureTest("certified timeout shrink fills from recent signers to preserve floor") {
    val result = ActiveFacilitatorAdmission.fromCertifiedTimeout(
      selected = List(a, b, c, d),
      recentSigners = window(
        10L -> Set(a, b, c),
        11L -> Set(a, b, c),
        12L -> Set(a, b, c)
      ),
      timeoutVoters = Set(c),
      minActiveSize = 3
    )

    expect.same(List(c, a, b), result.active) &&
    expect.same(Set(d), result.exclusions.collect { case e if e.reason == ExclusionReason.CertifiedTimeoutMissing => e.peerId }.toSet) &&
    expect(result.recentFilterApplied)
  }

  pureTest("certified timeout shrink is bypassed when retained set cannot satisfy floor") {
    val result = ActiveFacilitatorAdmission.fromCertifiedTimeout(
      selected = List(a, b, c),
      recentSigners = window(
        10L -> Set(a),
        11L -> Set(a),
        12L -> Set(a)
      ),
      timeoutVoters = Set(a),
      minActiveSize = 2
    )

    expect.same(List(a, b, c), result.active) &&
    expect(result.exclusions.isEmpty) &&
    expect(!result.recentFilterApplied)
  }
}
