package io.constellationnetwork.node.shared.infrastructure.consensus

import weaver.SimpleIOSuite

/** Pure-function coverage for the phase 2 view-from-time anchor consume side. Locks in: bootstrap fallback, clock-regression handling,
  * boundary arithmetic, and overflow saturation.
  */
object ViewFromTimeSuite extends SimpleIOSuite {

  private val IntervalMs: Long = 30000L // 30 seconds

  pureTest("parentEndTimeMs = None -> 0 (bootstrap / pre-phase-2 rollback falls through to phase 1)") {
    expect.same(0, ViewFromTime.compute(nowMs = 1_000_000L, parentEndTimeMs = None, viewIntervalMs = IntervalMs))
  }

  pureTest("delta = 0 -> 0 (round started exactly at parent.consensusEndTime, no skew)") {
    val now = 1_000_000L
    expect.same(0, ViewFromTime.compute(nowMs = now, parentEndTimeMs = Some(now), viewIntervalMs = IntervalMs))
  }

  pureTest("delta < 0 -> 0 (consumer-side clock regression; phase 1 still applies)") {
    val parent = 1_000_000L
    expect.same(0, ViewFromTime.compute(nowMs = parent - 5000L, parentEndTimeMs = Some(parent), viewIntervalMs = IntervalMs))
  }

  pureTest("delta = 1 interval -> 1") {
    val parent = 1_000_000L
    expect.same(1, ViewFromTime.compute(nowMs = parent + IntervalMs, parentEndTimeMs = Some(parent), viewIntervalMs = IntervalMs))
  }

  pureTest("delta = 5 intervals -> 5") {
    val parent = 1_000_000L
    expect.same(5, ViewFromTime.compute(nowMs = parent + 5L * IntervalMs, parentEndTimeMs = Some(parent), viewIntervalMs = IntervalMs))
  }

  pureTest("just under the next boundary -> previous integer view (floor semantics)") {
    val parent = 1_000_000L
    expect.same(2, ViewFromTime.compute(nowMs = parent + 3L * IntervalMs - 1L, parentEndTimeMs = Some(parent), viewIntervalMs = IntervalMs))
  }

  pureTest("ms-scale skew below interval resolution -> view unchanged") {
    val parent = 1_000_000L
    val a = ViewFromTime.compute(nowMs = parent + IntervalMs + 50L, parentEndTimeMs = Some(parent), viewIntervalMs = IntervalMs)
    val b = ViewFromTime.compute(nowMs = parent + IntervalMs + 250L, parentEndTimeMs = Some(parent), viewIntervalMs = IntervalMs)
    // Both well inside view=1's window; 200ms NTP skew between two peers doesn't bump them to different views.
    expect.same(1, a).and(expect.same(1, b))
  }

  pureTest("overflow saturates at Int.MaxValue instead of wrapping to a negative") {
    val parent = 0L
    // Pick a delta so big that delta / interval > Int.MaxValue. With interval=1, delta=Long.MaxValue is way beyond Int range.
    val view = ViewFromTime.compute(nowMs = Long.MaxValue, parentEndTimeMs = Some(parent), viewIntervalMs = 1L)
    expect.same(Int.MaxValue, view)
  }
}
