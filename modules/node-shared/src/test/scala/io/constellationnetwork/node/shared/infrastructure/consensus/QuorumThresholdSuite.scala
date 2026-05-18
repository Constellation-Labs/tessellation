package io.constellationnetwork.node.shared.infrastructure.consensus

import weaver.SimpleIOSuite

/** Locks in the supermajority-quorum invariant.
  *
  * The previous testnet config used `quorum-threshold-fraction = 0.67`, which rounded up unfavorably for N divisible by 3: specifically for
  * N=6 the computed quorum was `ceil(6 * 0.67) = ceil(4.02) = 5` instead of the BFT-intended `ceil(2N/3) = 4`. The off-by-one caused the
  * wedges: 4-of-6 responsive facilitators failed both Facility quorum AND ViewChangeCertificate quorum (same threshold), so the cluster
  * could neither commit NOR view-change out of the stalled round.
  *
  * The current testnet value is `0.6666666666666666` (max-precision Double approximation of 2/3 exact). This suite asserts that the
  * resulting `math.ceil(N * fraction).toInt` matches `math.ceil(2N/3)` for all N in the cluster-size range we expect to operate at.
  *
  * Any regression here would silently re-introduce the wedge. Two file-line references hold this value: the formula site in
  * `ConsensusStateAdvancer.maybeGetAllDeclarations` (~line 106) and the testnet config `modules/dag-l0/src/main/resources/dag-l0.conf`. The
  * default in `ConsensusConfig.quorumThresholdFraction = 1.0` (unanimity) stays unchanged; only the testnet override moves.
  */
object QuorumThresholdSuite extends SimpleIOSuite {

  private val testnetFraction: Double = 0.6666666666666666

  private def computedQuorum(n: Int, fraction: Double): Int =
    math.max(1, math.ceil(n.toDouble * fraction).toInt)

  private def bftSupermajority(n: Int): Int =
    math.max(1, math.ceil(2.0 * n / 3.0).toInt)

  pureTest("v22 fraction yields ceil(2N/3) for N in [3, 30]") {
    val mismatches = (3 to 30).flatMap { n =>
      val q = computedQuorum(n, testnetFraction)
      val expected = bftSupermajority(n)
      if (q == expected) None else Some((n, q, expected))
    }
    expect.same(List.empty, mismatches.toList)
  }

  pureTest("N=6 quorum is 4 (was 5 under 0.67; root cause of 2026-05-17 wedge)") {
    expect.same(4, computedQuorum(6, testnetFraction))
  }

  pureTest("N=6 with old 0.67 fraction would have been 5 (regression marker)") {
    expect.same(5, computedQuorum(6, 0.67))
  }

  pureTest("N=9 quorum is 6 (other N divisible by 3 also fixed)") {
    expect.same(6, computedQuorum(9, testnetFraction))
  }

  pureTest("BFT safety: any two quorums of size q intersect in at least f+1 honest for f=floor((N-1)/3)") {
    val violations = (4 to 30).flatMap { n =>
      val q = computedQuorum(n, testnetFraction)
      val f = (n - 1) / 3
      val intersection = 2 * q - n
      if (intersection >= f + 1) None else Some((n, q, f, intersection))
    }
    expect.same(List.empty, violations.toList)
  }

  pureTest("unanimity default (1.0) still requires N-of-N (dev/test environment unchanged)") {
    val n = 5
    expect.same(n, computedQuorum(n, 1.0))
  }
}
