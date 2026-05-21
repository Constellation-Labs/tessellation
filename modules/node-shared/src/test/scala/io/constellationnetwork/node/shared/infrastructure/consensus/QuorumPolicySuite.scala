package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.node.shared.infrastructure.consensus.state.QuorumPolicy

import weaver.SimpleIOSuite

/** Locks in the integer-supermajority formula `(2 * n + 2) / 3`.
  *
  * ==Why this suite exists==
  *
  * Prior to `QuorumPolicy`, every consensus quorum site computed `math.max(1, math.ceil(n.toDouble * quorumThresholdFraction).toInt)`, with
  * the testnet fraction pinned to `0.6666666666666666` (max-precision Double of 2/3). The pattern was duplicated across StallDetector,
  * StateTransitions, ProposalVccValidator, ConsensusStateAdvancer, and both env-specific advancers in dag-l0 and currency-l0.
  *
  * The codex GL0 scaled-liveness action plan (Phase 0 #2) calls for a single integer helper. This suite asserts that
  * `QuorumPolicy.supermajority(n) = (2 * n + 2) / 3` reproduces the legacy `ceil(n * 0.6666...)` result byte-identically for every cluster
  * size in the operating range, so the migration is safe.
  *
  * Any regression here would re-introduce the drift risk the helper was created to eliminate.
  */
object QuorumPolicySuite extends SimpleIOSuite {

  private val testnetFraction: Double = 0.6666666666666666

  private def legacyQuorum(n: Int, fraction: Double): Int =
    math.max(1, math.ceil(n.toDouble * fraction).toInt)

  pureTest("supermajority(0) == 0 (caller decides floor)") {
    expect.same(0, QuorumPolicy.supermajority(0))
  }

  pureTest("supermajority(1) == 1") {
    expect.same(1, QuorumPolicy.supermajority(1))
  }

  pureTest("supermajority(3) == 2 (classic 2-of-3 BFT minimum)") {
    expect.same(2, QuorumPolicy.supermajority(3))
  }

  pureTest("supermajority(5) == 4") {
    expect.same(4, QuorumPolicy.supermajority(5))
  }

  pureTest("supermajority(7) == 5") {
    expect.same(5, QuorumPolicy.supermajority(7))
  }

  pureTest("supermajority(15) == 10 (mainnet target)") {
    expect.same(10, QuorumPolicy.supermajority(15))
  }

  pureTest("supermajority(n) matches legacy ceil(n*0.6666...) for n in [1, 100]") {
    val mismatches = (1 to 100).flatMap { n =>
      val expected = legacyQuorum(n, testnetFraction)
      val got = math.max(1, QuorumPolicy.supermajority(n))
      if (got == expected) None else Some((n, got, expected))
    }
    expect.same(List.empty, mismatches.toList)
  }

  pureTest("supermajority(n) == ceil(2n/3) for n in [0, 100]") {
    val mismatches = (0 to 100).flatMap { n =>
      val expected = math.ceil(2.0 * n / 3.0).toInt
      val got = QuorumPolicy.supermajority(n)
      if (got == expected) None else Some((n, got, expected))
    }
    expect.same(List.empty, mismatches.toList)
  }

  pureTest("unanimity(n) == n for n >= 0") {
    val cases = List(0, 1, 3, 5, 9, 15, 31)
    val mismatches = cases.flatMap(n => if (QuorumPolicy.unanimity(n) == n) None else Some(n))
    expect.same(List.empty, mismatches)
  }

  pureTest("fromFraction matches legacy ceil for fraction=1.0 (dev unanimity)") {
    val mismatches = (1 to 30).flatMap { n =>
      val expected = math.ceil(n.toDouble * 1.0).toInt
      val got = QuorumPolicy.fromFraction(n, 1.0)
      if (got == expected) None else Some((n, got, expected))
    }
    expect.same(List.empty, mismatches.toList)
  }

  // ----------------------------------------------------------------------------
  // codex finding 1: fromFraction must now route 2/3 to integer supermajority,
  // not call ceil(n * 0.6666...) again. The tests below pin the dispatch so a
  // future drift between fromFraction(_, 2/3) and supermajority(_) is caught
  // by CI rather than at a 3am wedge.
  // ----------------------------------------------------------------------------

  pureTest("fromFraction(6, 2/3) == 4 (codex regression marker: historical 0.67 off-by-one wedge)") {
    expect.same(4, QuorumPolicy.fromFraction(6, 2.0 / 3.0))
  }

  pureTest("fromFraction(9, 2/3) == 6") {
    expect.same(6, QuorumPolicy.fromFraction(9, 2.0 / 3.0))
  }

  pureTest("fromFraction(12, 2/3) == 8") {
    expect.same(8, QuorumPolicy.fromFraction(12, 2.0 / 3.0))
  }

  pureTest("fromFraction(_, 2/3) byte-identical to supermajority(_) for n in [0, 100]") {
    val mismatches = (0 to 100).flatMap { n =>
      val viaFraction = QuorumPolicy.fromFraction(n, 2.0 / 3.0)
      val viaInteger = QuorumPolicy.supermajority(n)
      if (viaFraction == viaInteger) None else Some((n, viaFraction, viaInteger))
    }
    expect.same(List.empty, mismatches.toList)
  }

  pureTest("fromFraction(_, 1.0) byte-identical to unanimity(_) for n in [0, 100]") {
    val mismatches = (0 to 100).flatMap { n =>
      val viaFraction = QuorumPolicy.fromFraction(n, 1.0)
      val viaInteger = QuorumPolicy.unanimity(n)
      if (viaFraction == viaInteger) None else Some((n, viaFraction, viaInteger))
    }
    expect.same(List.empty, mismatches.toList)
  }

  pureTest("fromFraction accepts the testnet max-precision 2/3 literal 0.6666666666666666") {
    // Belt-and-suspenders: the on-disk config writes the literal Double, not the expression
    // `2.0 / 3.0`. Both must resolve to the same supermajority value via the epsilon dispatch.
    val mismatches = (3 to 30).flatMap { n =>
      val viaLiteral = QuorumPolicy.fromFraction(n, 0.6666666666666666)
      val viaInteger = QuorumPolicy.supermajority(n)
      if (viaLiteral == viaInteger) None else Some((n, viaLiteral, viaInteger))
    }
    expect.same(List.empty, mismatches.toList)
  }

  pureTest("fromFraction rejects 0.5 with IllegalArgumentException (named-mode discipline)") {
    // A non-named fraction must not silently bypass the QuorumPolicy/schema-hash discipline.
    // Operators who legitimately need a new threshold must add a named mode and route it.
    val attempt = scala.util.Try(QuorumPolicy.fromFraction(6, 0.5))
    expect.all(
      attempt.isFailure,
      attempt.failed.toOption.exists(_.isInstanceOf[IllegalArgumentException])
    )
  }

  pureTest("fromFraction rejects 0.7 with IllegalArgumentException") {
    val attempt = scala.util.Try(QuorumPolicy.fromFraction(6, 0.7))
    expect.all(
      attempt.isFailure,
      attempt.failed.toOption.exists(_.isInstanceOf[IllegalArgumentException])
    )
  }

  pureTest("fromFraction with n<=0 short-circuits to 0 even for unsupported fractions (no throw)") {
    // Defensive: the n<=0 fast path takes precedence over the fraction validation so an empty
    // cluster cannot surface a misleading IllegalArgumentException from the dispatch.
    expect.all(
      QuorumPolicy.fromFraction(0, 0.5) == 0,
      QuorumPolicy.fromFraction(-3, 0.5) == 0
    )
  }

  pureTest("BFT safety: any two quorums of size q intersect in at least f+1 honest for f=floor((n-1)/3)") {
    val violations = (4 to 100).flatMap { n =>
      val q = math.max(1, QuorumPolicy.supermajority(n))
      val f = (n - 1) / 3
      val intersection = 2 * q - n
      if (intersection >= f + 1) None else Some((n, q, f, intersection))
    }
    expect.same(List.empty, violations.toList)
  }

  pureTest("regression marker: n=6 supermajority == 4 (not 5 as under the buggy 0.67 fraction)") {
    expect.same(4, QuorumPolicy.supermajority(6))
    expect.same(5, legacyQuorum(6, 0.67))
  }
}
