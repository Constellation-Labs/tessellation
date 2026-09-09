package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.SimpleIOSuite

object SigningMembershipSuite extends SimpleIOSuite {

  private def peer(c: Char): PeerId = PeerId(Hex(c.toString * 128))
  private def ordinal(value: Long): SnapshotOrdinal = SnapshotOrdinal.unsafeApply(value)

  private val a = peer('a')
  private val b = peer('b')
  private val c = peer('c')
  private val d = peer('d')
  private val e = peer('e')

  private val recentAB: SortedMap[SnapshotOrdinal, SortedSet[PeerId]] = SortedMap(
    ordinal(1L) -> SortedSet(a, b),
    ordinal(2L) -> SortedSet(a, b),
    ordinal(3L) -> SortedSet(a, b)
  )

  private def classify(
    selected: List[PeerId],
    maxExpansionPerRound: Int = 0,
    minProbationReentrySlots: Int = 0
  ): ActiveFacilitatorAdmission.Result =
    ActiveFacilitatorAdmission.fromRecentSigners(
      selected = selected,
      recentSigners = recentAB,
      peerQuality = Map(a -> (3, 3), b -> (3, 3)),
      activeScores = Map(a -> 150, b -> 150),
      minActiveSize = 2,
      targetActiveSize = 2,
      maxActiveSize = selected.size,
      minParticipationObservations = 3,
      minParticipationRatio = 0.5,
      maxExpansionPerRound = maxExpansionPerRound,
      minProbationReentrySlots = minProbationReentrySlots
    )

  pureTest("score exclusions become Tier-1 classifications instead of deleted signing seats") {
    val selected = List(a, b, c, d)
    val membership = ConsensusPeerController.retainSelectedForSigning(selected, classify(selected))

    expect.same(selected, membership.retained) &&
    expect.same(Set(c, d), membership.nonCore)
  }

  pureTest("a probation admission remains seated but cannot enter Core") {
    val selected = List(a, b, c)
    val classification = classify(selected, maxExpansionPerRound = 1, minProbationReentrySlots = 1)
    val membership = ConsensusPeerController.retainSelectedForSigning(selected, classification)

    expect(classification.probationAdmitted.contains(c)) &&
    expect(membership.retained.contains(c)) &&
    expect(membership.nonCore.contains(c))
  }

  pureTest("one certified admission grows the signing committee monotonically") {
    val parent = List(a, b, c, d)
    val selected = ConsensusPeerController.applyCertifiedAdmissions(parent, Set(e))
    val membership = ConsensusPeerController.retainSelectedForSigning(selected, classify(selected))

    expect.same(parent :+ e, membership.retained) &&
    expect.same(parent.size + 1, membership.retained.size) &&
    expect(membership.nonCore.contains(e))
  }

  pureTest("controller exclusion remains a Tier-1 classification and cannot delete the signing lease") {
    val selected = List(a, b, c)
    val membership = ConsensusPeerController.retainSelectedForSigning(selected, classify(selected))

    expect(membership.retained.contains(c)) && expect(membership.nonCore.contains(c))
  }
}
