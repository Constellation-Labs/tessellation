package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{ControllerEvidenceEntry, SnapshotOrdinal}
import io.constellationnetwork.security.hex.Hex

import weaver.SimpleIOSuite

/** Guards the reward-decoupling filter: probation re-entry seats (score-0 climbers) must be excluded from the node-operator reward pool
  * until their evidence-derived score reaches the promote threshold, while every degenerate regime (no window, empty window, nobody
  * qualified) falls back to the pre-change pay-everyone behavior so the reward pool can never silently zero out.
  */
object RewardQualifiedFacilitatorsSuite extends SimpleIOSuite {

  private def peer(c: Char): PeerId = PeerId(Hex(c.toString * 128))
  private def ord(n: Long): SnapshotOrdinal = SnapshotOrdinal.unsafeApply(n)

  private val a = peer('a')
  private val b = peer('b')
  private val c = peer('c')

  private val PromoteThreshold = 100

  private def entry(roundStart: Set[PeerId], completed: Set[PeerId]): ControllerEvidenceEntry =
    ControllerEvidenceEntry(
      roundStartFacilitators = SortedSet.from(roundStart),
      completedSigners = SortedSet.from(completed),
      timeoutVoters = SortedSet.empty,
      admittedPeers = SortedSet.empty,
      evictedPeers = SortedSet.empty
    )

  private def window(entries: (Long, ControllerEvidenceEntry)*): SortedMap[SnapshotOrdinal, ControllerEvidenceEntry] =
    SortedMap.from(entries.toList.map { case (o, e) => ord(o) -> e })

  pureTest("absent evidence window qualifies every facilitator (bootstrap fallback)") {
    val result = ControllerEvidenceDerivation.rewardQualifiedFacilitators(
      facilitators = SortedSet(a, b, c),
      evidence = None,
      promoteThreshold = PromoteThreshold
    )

    expect.same(SortedSet(a, b, c), result)
  }

  pureTest("empty evidence window qualifies every facilitator (pre-deploy snapshot fallback)") {
    val result = ControllerEvidenceDerivation.rewardQualifiedFacilitators(
      facilitators = SortedSet(a, b, c),
      evidence = Some(SortedMap.empty[SnapshotOrdinal, ControllerEvidenceEntry]),
      promoteThreshold = PromoteThreshold
    )

    expect.same(SortedSet(a, b, c), result)
  }

  pureTest("filters climbing and unproven facilitators below the promote threshold") {
    // a signs all 5 entries: 5 * SignWeight(20) = 100 = promote -> qualified.
    // b enters at ordinal 14 and signs 2: 2 * 20 = 40 < 100 -> climbing, not yet earning.
    // c is a facilitator this round but absent from the whole window (fresh probation seat) -> no
    // derived entry -> not earning.
    val evidence = window(
      (11L, entry(roundStart = Set(a), completed = Set(a))),
      (12L, entry(roundStart = Set(a), completed = Set(a))),
      (13L, entry(roundStart = Set(a), completed = Set(a))),
      (14L, entry(roundStart = Set(a, b), completed = Set(a, b))),
      (15L, entry(roundStart = Set(a, b), completed = Set(a, b)))
    )

    val result = ControllerEvidenceDerivation.rewardQualifiedFacilitators(
      facilitators = SortedSet(a, b, c),
      evidence = Some(evidence),
      promoteThreshold = PromoteThreshold
    )

    expect
      .same(SortedSet(a), result)
      .and(expect(!result.contains(b), "b is climbing (score 40 < 100) and must not earn yet"))
      .and(expect(!result.contains(c), "c has no evidence entry (fresh probation seat) and must not earn yet"))
  }

  pureTest("falls back to paying everyone when no facilitator meets the threshold (post-restart refill)") {
    // Two-entry window: even a perfect signer holds 2 * 20 = 40 < 100. The filter must not zero
    // the reward pool while the window refills after a cold restart.
    val evidence = window(
      (21L, entry(roundStart = Set(a, b), completed = Set(a, b))),
      (22L, entry(roundStart = Set(a, b), completed = Set(a, b)))
    )

    val result = ControllerEvidenceDerivation.rewardQualifiedFacilitators(
      facilitators = SortedSet(a, b),
      evidence = Some(evidence),
      promoteThreshold = PromoteThreshold
    )

    expect.same(SortedSet(a, b), result)
  }

  pureTest("misses subtract from the derived score and hold a flaky signer below the threshold") {
    // b is in every round-start but signs only 4 of 6: 4 * 20 - 2 * MissWeight(15) = 50 < 100.
    // a signs 5 of 6 with one miss: 5 * 20 - 15 = 85 < 100 -> also below; 6/6 for a variant with
    // a clean sixth round: use six entries where a signs all six: 6 * 20 = 120 >= 100.
    val evidence = window(
      (31L, entry(roundStart = Set(a, b), completed = Set(a, b))),
      (32L, entry(roundStart = Set(a, b), completed = Set(a))),
      (33L, entry(roundStart = Set(a, b), completed = Set(a, b))),
      (34L, entry(roundStart = Set(a, b), completed = Set(a))),
      (35L, entry(roundStart = Set(a, b), completed = Set(a, b))),
      (36L, entry(roundStart = Set(a, b), completed = Set(a, b)))
    )

    val result = ControllerEvidenceDerivation.rewardQualifiedFacilitators(
      facilitators = SortedSet(a, b),
      evidence = Some(evidence),
      promoteThreshold = PromoteThreshold
    )

    expect
      .same(SortedSet(a), result)
      .and(expect(!result.contains(b), "b signed 4/6 with 2 misses (score 50) and must stay below the threshold"))
  }
}
