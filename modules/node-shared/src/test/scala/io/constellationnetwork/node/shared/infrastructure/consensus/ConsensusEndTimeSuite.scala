package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.Facility
import io.constellationnetwork.node.shared.infrastructure.consensus.state.Candidates
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.hash.Hash

import weaver.SimpleIOSuite

/** Locks in the v19 phase 2 view-from-time anchor contract.
  *
  * Each test pins one rule from the design: strict-majority threshold, integer lower-median (deterministic on even counts), Bitcoin
  * MTP-style clamp, and bootstrap parent-None handling.
  */
object ConsensusEndTimeSuite extends SimpleIOSuite {

  private def facilityWithClock(clockMs: Option[Long]): Facility =
    Facility(
      eventHashes = Set.empty[Hash],
      candidates = Candidates(Set.empty),
      trigger = None,
      facilitatorsHash = Hash.empty,
      lastGlobalSnapshotOrdinal = SnapshotOrdinal.MinValue,
      lastSnapshotHash = Hash.empty,
      consensusConfigHash = None,
      selfHealthHint = None,
      proposerClockMs = clockMs
    )

  pureTest("returns None when below the strict-majority threshold") {
    // 5 facilities, only 2 carry clocks. Threshold = 5/2 + 1 = 3. 2 < 3 -> None.
    val facilities = List(
      facilityWithClock(Some(100L)),
      facilityWithClock(Some(200L)),
      facilityWithClock(None),
      facilityWithClock(None),
      facilityWithClock(None)
    )
    expect.same(None, ConsensusEndTime.compute(facilities, parentEndTime = None))
  }

  pureTest("returns the integer lower-median when at or above the threshold (no clamp)") {
    // 5 facilities, 5 clocks present. Sorted: [100, 200, 300, 400, 500].
    // medianIdx = 5/2 = 2, sorted(2) = 300.
    val facilities = List(
      facilityWithClock(Some(500L)),
      facilityWithClock(Some(100L)),
      facilityWithClock(Some(400L)),
      facilityWithClock(Some(200L)),
      facilityWithClock(Some(300L))
    )
    expect.same(Some(300L), ConsensusEndTime.compute(facilities, parentEndTime = None))
  }

  pureTest("lower-median on even-count sets is deterministic (no rounding)") {
    // 4 clocks: [10, 20, 30, 40]. medianIdx = 4/2 = 2, sorted(2) = 30.
    // (Upper median would be 30 too; lower median picked for n=4 even is the third element.)
    val facilities = List(
      facilityWithClock(Some(40L)),
      facilityWithClock(Some(20L)),
      facilityWithClock(Some(10L)),
      facilityWithClock(Some(30L))
    )
    // Threshold for n=4 = 4/2 + 1 = 3, and 4 >= 3, so it computes.
    expect.same(Some(30L), ConsensusEndTime.compute(facilities, parentEndTime = None))
  }

  pureTest("Bitcoin MTP clamp: result >= parentEndTime + 1 even if median is below parent") {
    // Three facilities with clocks [50, 60, 70], median = 60. Parent = 1000.
    // Clamp: max(60, 1001) = 1001.
    val facilities = List(
      facilityWithClock(Some(50L)),
      facilityWithClock(Some(60L)),
      facilityWithClock(Some(70L))
    )
    expect.same(Some(1001L), ConsensusEndTime.compute(facilities, parentEndTime = Some(1000L)))
  }

  pureTest("clamp lets the median through when it already exceeds parent + 1") {
    // Median 500, parent 100. max(500, 101) = 500.
    val facilities = List(
      facilityWithClock(Some(400L)),
      facilityWithClock(Some(500L)),
      facilityWithClock(Some(600L))
    )
    expect.same(Some(500L), ConsensusEndTime.compute(facilities, parentEndTime = Some(100L)))
  }

  pureTest("parentEndTime=None (bootstrap) skips the clamp") {
    // Median 50, no parent. Result = 50 unchanged.
    val facilities = List(
      facilityWithClock(Some(40L)),
      facilityWithClock(Some(50L)),
      facilityWithClock(Some(60L))
    )
    expect.same(Some(50L), ConsensusEndTime.compute(facilities, parentEndTime = None))
  }

  pureTest("None proposerClockMs values are skipped, threshold computed on the present count") {
    // 6 facilities, 4 carry clocks. Threshold against n=6 = 6/2 + 1 = 4. 4 >= 4 -> proceeds.
    // Clocks present: [100, 200, 300, 400]. medianIdx = 4/2 = 2, sorted(2) = 300.
    val facilities = List(
      facilityWithClock(Some(100L)),
      facilityWithClock(None),
      facilityWithClock(Some(200L)),
      facilityWithClock(None),
      facilityWithClock(Some(300L)),
      facilityWithClock(Some(400L))
    )
    expect.same(Some(300L), ConsensusEndTime.compute(facilities, parentEndTime = None))
  }

  pureTest("single facility carrying a clock and only 1 in n=1 set computes (threshold 1)") {
    // Degenerate single-facility round: n=1, threshold = 0+1 = 1. Median = the single clock.
    val facilities = List(facilityWithClock(Some(777L)))
    expect.same(Some(777L), ConsensusEndTime.compute(facilities, parentEndTime = None))
  }

  pureTest("empty facility set returns None") {
    expect.same(None, ConsensusEndTime.compute(List.empty[Facility], parentEndTime = None))
  }

  pureTest("clamp applies even when parent is exactly equal to median") {
    // median = 100, parent = 100. max(100, 101) = 101.
    val facilities = List(
      facilityWithClock(Some(90L)),
      facilityWithClock(Some(100L)),
      facilityWithClock(Some(110L))
    )
    expect.same(Some(101L), ConsensusEndTime.compute(facilities, parentEndTime = Some(100L)))
  }

  pureTest("order-independence: shuffling input does not change the result") {
    val a = facilityWithClock(Some(500L))
    val b = facilityWithClock(Some(100L))
    val c = facilityWithClock(Some(300L))
    val original = ConsensusEndTime.compute(List(a, b, c), parentEndTime = None)
    val shuffled = ConsensusEndTime.compute(List(c, a, b), parentEndTime = None)
    expect.same(original, shuffled).and(expect.same(Some(300L), original))
  }

  pureTest("v35 leader-proposed time is validated only against signed parent time and deterministic bounds") {
    val result = ConsensusEndTime.validateProposed(
      proposed = Some(1200L),
      parentEndTime = Some(1000L),
      committedView = 0L,
      viewInterval = 10.seconds,
      maxRoundDuration = Some(1.second)
    )

    expect.same(Right(()), result)
  }

  pureTest("v35 rejects a non-monotonic or above-bound leader-proposed time") {
    val nonMonotonic = ConsensusEndTime.validateProposed(
      proposed = Some(1000L),
      parentEndTime = Some(1000L),
      committedView = 0L,
      viewInterval = 1.second,
      maxRoundDuration = Some(1.second)
    )
    val aboveBound = ConsensusEndTime.validateProposed(
      proposed = Some(2001L),
      parentEndTime = Some(1000L),
      committedView = 0L,
      viewInterval = 1.second,
      maxRoundDuration = Some(1.second)
    )

    expect
      .same(Left("consensus_end_time_not_monotonic"), nonMonotonic)
      .and(expect.same(Left("consensus_end_time_above_view_bound"), aboveBound))
  }

  pureTest("v35 committed views expand the deterministic end-time allowance") {
    val result = ConsensusEndTime.validateProposed(
      proposed = Some(3000L),
      parentEndTime = Some(1000L),
      committedView = 1L,
      viewInterval = 1.second,
      maxRoundDuration = Some(1.second)
    )

    expect.same(Right(()), result)
  }
}
