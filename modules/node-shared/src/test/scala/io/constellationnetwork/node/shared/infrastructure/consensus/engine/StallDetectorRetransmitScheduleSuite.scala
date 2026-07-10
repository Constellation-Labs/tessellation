package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import scala.concurrent.duration._

import weaver.FunSuite

/** v13 Facility retransmit schedule — pure-function tests for `StallDetector.nextRetransmitDelay`.
  *
  * Pre-v13 retransmit fired on every stall cycle (~30s cadence determined by `declarationTimeout`), so 3 retries took ~90s. The May 2
  * fork-recovery E2E ord-6 stall ate ~3 minutes because gossip-mesh-dropped Facility decls weren't pushed back into the network fast enough
  * during the high-jitter cold-start window. v13 switches to capped exponential backoff: 5s, 10s, 20s, 30s, 30s, ... — the first three
  * attempts fire in ~35s instead of ~90s.
  */
object StallDetectorRetransmitScheduleSuite extends FunSuite {

  test("attempt 0 returns the initial delay (5s)") {
    expect.same(5.seconds, StallDetector.nextRetransmitDelay(0))
  }

  test("attempt 1 doubles to 10s") {
    expect.same(10.seconds, StallDetector.nextRetransmitDelay(1))
  }

  test("attempt 2 doubles to 20s") {
    expect.same(20.seconds, StallDetector.nextRetransmitDelay(2))
  }

  test("attempt 3 hits the cap at 30s (would otherwise be 40s)") {
    expect.same(30.seconds, StallDetector.nextRetransmitDelay(3))
  }

  test("attempt 4 stays at the cap (30s)") {
    expect.same(30.seconds, StallDetector.nextRetransmitDelay(4))
  }

  test("attempt 10 stays at the cap (no overflow)") {
    expect.same(30.seconds, StallDetector.nextRetransmitDelay(10))
  }

  test("absurdly large attempt stays at the cap (overflow guard)") {
    expect.same(30.seconds, StallDetector.nextRetransmitDelay(Int.MaxValue))
  }

  test("negative attempt returns the initial delay (defensive)") {
    expect.same(StallDetector.FacilityRetransmitInitialDelay, StallDetector.nextRetransmitDelay(-1))
  }

  test("MaxFacilityRetransmits raised to 5 in v13 (regression: do not silently lower)") {
    expect.same(5, StallDetector.MaxFacilityRetransmits)
  }

  test("cumulative wall-time of 5 attempts: 5+10+20+30+30 = 95 seconds") {
    val total = (0 until StallDetector.MaxFacilityRetransmits).map(StallDetector.nextRetransmitDelay).reduce(_ + _)
    expect.same(95.seconds, total)
  }

  test("first three attempts fire within 35 seconds (the key v13 improvement)") {
    val firstThree = (0 until 3).map(StallDetector.nextRetransmitDelay).reduce(_ + _)
    expect.same(35.seconds, firstThree)
  }
}
