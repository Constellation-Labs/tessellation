package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.infrastructure.consensus.SignatureGraceDecision._

import weaver.FunSuite

/** State-machine coverage for the three-way signature-grace decision. The alpha.153 finalization change shipped with no direct test; this
  * pins all four transitions, including the Core-incomplete -> Core-complete case that finding 1 was about.
  */
object SignatureGraceDecisionSuite extends FunSuite {

  private val tier1: FiniteDuration = 750.milliseconds
  private val full: FiniteDuration = 3.seconds
  private def at(ms: Long): FiniteDuration = ms.milliseconds
  private def stampOf(e: Eval): Option[Stamp] = e.update match {
    case Set(s) => Some(s)
    case _      => None
  }

  test("no quorum yet: Leave the stamp untouched, do not wait, not observed") {
    val e = evaluate(at(100), validCount = 1, canFinalize = false, fullCommitteeSigned = false, coreComplete = false, None, tier1, full)
    expect(e.update == Leave) && expect(!e.waitMore) && expect(!e.firstObserved)
  }

  test("full committee signed: Clear and finalize immediately") {
    val e = evaluate(at(100), validCount = 7, canFinalize = true, fullCommitteeSigned = true, coreComplete = true, None, tier1, full)
    expect(e.update == Clear) && expect(!e.waitMore) && expect(e.firstObserved) && expect.same(7, e.firstQuorumCount)
  }

  test("Core complete but not full: waits only the short Tier-1 window") {
    val first = evaluate(at(0), 3, canFinalize = true, fullCommitteeSigned = false, coreComplete = true, None, tier1, full)
    val within = evaluate(at(740), 3, canFinalize = true, fullCommitteeSigned = false, coreComplete = true, stampOf(first), tier1, full)
    val past = evaluate(at(760), 3, canFinalize = true, fullCommitteeSigned = false, coreComplete = true, stampOf(first), tier1, full)
    expect(first.waitMore) && expect(first.firstObserved) && expect(within.waitMore) && expect(!past.waitMore)
  }

  test("Core incomplete: waits the full window even past the Tier-1 window") {
    val first = evaluate(at(0), 2, canFinalize = true, fullCommitteeSigned = false, coreComplete = false, None, tier1, full)
    val pastTier1 = evaluate(at(800), 2, canFinalize = true, fullCommitteeSigned = false, coreComplete = false, stampOf(first), tier1, full)
    val pastFull = evaluate(at(3100), 2, canFinalize = true, fullCommitteeSigned = false, coreComplete = false, stampOf(first), tier1, full)
    expect(first.waitMore) && expect(pastTier1.waitMore) && expect(!pastFull.waitMore)
  }

  test("Core-incomplete then Core-complete: Tier-1 window measured from Core completion, not first quorum (alpha.153 fix)") {
    // Quorum first seen at t=0 with Core incomplete; Core completes at t=2000, well past the 750ms Tier-1 window.
    val q0 = evaluate(at(0), 2, canFinalize = true, fullCommitteeSigned = false, coreComplete = false, None, tier1, full)
    val coreDone = evaluate(at(2000), 3, canFinalize = true, fullCommitteeSigned = false, coreComplete = true, stampOf(q0), tier1, full)
    // Must NOT finalize immediately: the window is fresh from t=2000, not elapsed-since-t=0.
    val within = evaluate(at(2740), 3, canFinalize = true, fullCommitteeSigned = false, coreComplete = true, stampOf(coreDone), tier1, full)
    val past = evaluate(at(2760), 3, canFinalize = true, fullCommitteeSigned = false, coreComplete = true, stampOf(coreDone), tier1, full)
    expect(coreDone.waitMore, s"Core completing late should start a fresh Tier-1 window, got $coreDone") &&
    expect(within.waitMore) &&
    expect(!past.waitMore)
  }
}
