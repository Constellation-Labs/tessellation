package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.FunSuite

/** Alpha.91 regression coverage for `StallDetector.computeCoreQuorumStatus` -- the Core-only quorum-infeasibility gate that replaces the
  * pre-alpha.91 full-facilitator denominator.
  *
  * The bug being locked: pre-alpha.91 StallDetector computed `quorumInfeasible = (totalFacilitators
  *   - missingPeers.size) < ceil(totalFacilitators * fraction)`. Post-alpha.89 the phase-quorum advancer gated on Core only, so a 3/3 Core
  *     could close a 2-of-3 phase quorum -- but StallDetector kept abandoning the round first because it saw `5 active < 6 required`
  *     against the full 8-facilitator denominator. Observed post-alpha.90 at ord 3127058 stuck for 30+ min.
  *
  * The post-deploy abandon reason this suite locks against: `ROUND_ABANDONED reason=quorum infeasible: 2 active < 6 required
  * (clusterSize=8)`
  */
object StallDetectorCoreQuorumSuite extends FunSuite {

  private val Supermajority: Double = 2.0 / 3.0
  private val Unanimity: Double = 1.0

  private def pid(hex: String): PeerId = PeerId(Hex(hex))

  // Hex-sortable IDs for readable expectations.
  private val core1: PeerId = pid("01" * 64)
  private val core2: PeerId = pid("02" * 64)
  private val core3: PeerId = pid("03" * 64)
  private val tier1a: PeerId = pid("0a" * 64)
  private val tier1b: PeerId = pid("0b" * 64)
  private val tier1c: PeerId = pid("0c" * 64)
  private val tier1d: PeerId = pid("0d" * 64)
  private val tier1e: PeerId = pid("0e" * 64)

  private val core3of3: Set[PeerId] = Set(core1, core2, core3)
  private val tier1of5: Set[PeerId] = Set(tier1a, tier1b, tier1c, tier1d, tier1e)

  // ---------------------------------------------------------------------------
  // The regression-locking case: Core has quorum, Tier 1 is silent
  // ---------------------------------------------------------------------------

  test("Core has quorum even when ALL Tier 1 peers are missing (the alpha.90->.91 regression)") {
    // The post-alpha.90 ord 3127058 scenario: cluster of 8 facilitators (3 Core + 5 Tier 1),
    // Core 3/3 healthy, all 5 Tier 1 peers silent. Pre-alpha.91 this abandoned with
    // "3 active < 6 required (clusterSize=8)" because the gate used the full-facilitator
    // denominator. Post-alpha.91 the gate sees coreRemaining=3 >= coreRequired=2 and stays
    // feasible.
    val status = StallDetector.computeCoreQuorumStatus(
      activeCore = core3of3,
      missingPeers = tier1of5,
      quorumThresholdFraction = Supermajority
    )
    expect
      .same(3, status.coreSize)
      .and(expect.same(3, status.coreRemaining))
      .and(expect.same(2, status.coreRequired))
      .and(expect(!status.quorumInfeasible, "Core has 3/3 signers; round must NOT be quorum-infeasible despite 5 missing Tier 1 peers"))
  }

  // ---------------------------------------------------------------------------
  // Core-loss scenarios that legitimately make quorum infeasible
  // ---------------------------------------------------------------------------

  test("losing 1 Core peer (supermajority 2/3) keeps quorum feasible: coreRemaining=2 >= coreRequired=2") {
    val status = StallDetector.computeCoreQuorumStatus(
      activeCore = core3of3,
      missingPeers = Set(core1),
      quorumThresholdFraction = Supermajority
    )
    expect
      .same(3, status.coreSize)
      .and(expect.same(2, status.coreRemaining))
      .and(expect.same(2, status.coreRequired))
      .and(expect(!status.quorumInfeasible, "2-of-3 is the BFT quorum minimum; must stay feasible"))
  }

  test("losing 2 Core peers (supermajority 2/3) trips infeasibility: coreRemaining=1 < coreRequired=2") {
    val status = StallDetector.computeCoreQuorumStatus(
      activeCore = core3of3,
      missingPeers = Set(core1, core2),
      quorumThresholdFraction = Supermajority
    )
    expect
      .same(3, status.coreSize)
      .and(expect.same(1, status.coreRemaining))
      .and(expect.same(2, status.coreRequired))
      .and(expect(status.quorumInfeasible, "Only 1 of 3 Core peers active; cannot reach 2/3 quorum"))
  }

  test("losing ALL Core peers trips infeasibility: coreRemaining=0 < coreRequired=2") {
    val status = StallDetector.computeCoreQuorumStatus(
      activeCore = core3of3,
      missingPeers = core3of3,
      quorumThresholdFraction = Supermajority
    )
    expect
      .same(0, status.coreRemaining)
      .and(expect.same(2, status.coreRequired))
      .and(expect(status.quorumInfeasible, "All Core peers missing; round is starved"))
  }

  // ---------------------------------------------------------------------------
  // Mixed-loss scenarios: Core + Tier 1 missing -- only the Core count matters
  // ---------------------------------------------------------------------------

  test("mixed: 1 Core + ALL Tier 1 missing -- still feasible since Core has 2 of 3") {
    val status = StallDetector.computeCoreQuorumStatus(
      activeCore = core3of3,
      missingPeers = Set(core1) ++ tier1of5,
      quorumThresholdFraction = Supermajority
    )
    expect
      .same(3, status.coreSize)
      .and(expect.same(2, status.coreRemaining))
      .and(expect.same(2, status.coreRequired))
      .and(expect(!status.quorumInfeasible, "Core has 2/3, satisfies BFT supermajority; Tier 1 absence is irrelevant"))
  }

  test("mixed: 2 Core + 3 Tier 1 missing -- Core has only 1, infeasible") {
    val status = StallDetector.computeCoreQuorumStatus(
      activeCore = core3of3,
      missingPeers = Set(core1, core2, tier1a, tier1b, tier1c),
      quorumThresholdFraction = Supermajority
    )
    expect
      .same(1, status.coreRemaining)
      .and(expect.same(2, status.coreRequired))
      .and(expect(status.quorumInfeasible, "Core falls below quorum even though some Tier 1 peers signed"))
  }

  // ---------------------------------------------------------------------------
  // Tier 1 has no effect on the gate
  // ---------------------------------------------------------------------------

  test("Tier 1 peers in missingPeers but not in activeCore do not affect quorumRemaining") {
    // Pure observability: tier1-only missing must produce coreRemaining == coreSize.
    val onlyTier1Missing = tier1of5 + pid("ff" * 64) // ff is an outsider, also ignored
    val status = StallDetector.computeCoreQuorumStatus(
      activeCore = core3of3,
      missingPeers = onlyTier1Missing,
      quorumThresholdFraction = Supermajority
    )
    expect
      .same(3, status.coreRemaining)
      .and(expect(!status.quorumInfeasible))
  }

  // ---------------------------------------------------------------------------
  // Threshold dispatch: supermajority vs unanimity
  // ---------------------------------------------------------------------------

  test("unanimity (1.0) requires ALL Core peers: losing 1 of 3 trips infeasibility") {
    val status = StallDetector.computeCoreQuorumStatus(
      activeCore = core3of3,
      missingPeers = Set(core1),
      quorumThresholdFraction = Unanimity
    )
    expect
      .same(3, status.coreSize)
      .and(expect.same(2, status.coreRemaining))
      .and(expect.same(3, status.coreRequired))
      .and(expect(status.quorumInfeasible, "Unanimity policy requires all 3; missing 1 trips the gate"))
  }

  test("unanimity (1.0) with full Core healthy: feasible regardless of Tier 1") {
    val status = StallDetector.computeCoreQuorumStatus(
      activeCore = core3of3,
      missingPeers = tier1of5,
      quorumThresholdFraction = Unanimity
    )
    expect
      .same(3, status.coreRemaining)
      .and(expect.same(3, status.coreRequired))
      .and(expect(!status.quorumInfeasible))
  }

  // ---------------------------------------------------------------------------
  // Edge cases
  // ---------------------------------------------------------------------------

  test("empty Core: coreRequired floors at 1 to avoid 0 < 0 false-negative") {
    val status = StallDetector.computeCoreQuorumStatus(
      activeCore = Set.empty[PeerId],
      missingPeers = Set.empty[PeerId],
      quorumThresholdFraction = Supermajority
    )
    expect
      .same(0, status.coreSize)
      .and(expect.same(0, status.coreRemaining))
      .and(expect.same(1, status.coreRequired))
      .and(expect(status.quorumInfeasible, "0 < 1: an empty Core cannot reach quorum"))
  }

  test("single-Core: any miss is infeasible") {
    val status = StallDetector.computeCoreQuorumStatus(
      activeCore = Set(core1),
      missingPeers = Set(core1),
      quorumThresholdFraction = Supermajority
    )
    expect
      .same(1, status.coreSize)
      .and(expect.same(0, status.coreRemaining))
      .and(expect.same(1, status.coreRequired))
      .and(expect(status.quorumInfeasible))
  }

  test("larger Core (5 of 5): supermajority requires 4 -- 1 missing keeps feasibility") {
    val coreFive: Set[PeerId] = core3of3 + tier1a + tier1b // reusing PeerIds, just for the set
    val status = StallDetector.computeCoreQuorumStatus(
      activeCore = coreFive,
      missingPeers = Set(core1),
      quorumThresholdFraction = Supermajority
    )
    expect
      .same(5, status.coreSize)
      .and(expect.same(4, status.coreRemaining))
      .and(expect.same(4, status.coreRequired))
      .and(expect(!status.quorumInfeasible))
  }

  test("larger Core (5 of 5): losing 2 trips infeasibility (4 required, only 3 active)") {
    val coreFive: Set[PeerId] = core3of3 + tier1a + tier1b
    val status = StallDetector.computeCoreQuorumStatus(
      activeCore = coreFive,
      missingPeers = Set(core1, core2),
      quorumThresholdFraction = Supermajority
    )
    expect
      .same(3, status.coreRemaining)
      .and(expect.same(4, status.coreRequired))
      .and(expect(status.quorumInfeasible))
  }
}
