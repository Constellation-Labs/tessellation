package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.infrastructure.consensus.SignatureGraceDecision._

import weaver.FunSuite

object SignatureGraceDecisionSuite extends FunSuite {

  private val window: FiniteDuration = 3.seconds
  private def at(ms: Long): FiniteDuration = ms.milliseconds
  private def stampOf(e: Eval): Option[Stamp] = e.update match {
    case Set(s) => Some(s)
    case _      => None
  }

  test("no quorum yet: Leave the stamp untouched, do not wait, not observed") {
    val e = evaluate(at(100), validCount = 1, canFinalize = false, fullCommitteeSigned = false, None, window)
    expect(e.update == Leave) && expect(!e.waitMore) && expect(!e.firstObserved)
  }

  test("full committee signed: Clear and finalize immediately") {
    val e = evaluate(at(100), validCount = 7, canFinalize = true, fullCommitteeSigned = true, None, window)
    expect(e.update == Clear) && expect(!e.waitMore) && expect(e.firstObserved) && expect.same(7, e.firstQuorumCount)
  }

  test("quorum but not full: waits the window measured from first quorum") {
    val first = evaluate(at(0), 3, canFinalize = true, fullCommitteeSigned = false, None, window)
    val within = evaluate(at(2990), 4, canFinalize = true, fullCommitteeSigned = false, stampOf(first), window)
    val past = evaluate(at(3010), 4, canFinalize = true, fullCommitteeSigned = false, stampOf(within), window)
    expect(first.waitMore) &&
    expect(first.firstObserved) &&
    expect(within.waitMore) &&
    expect(!within.firstObserved) &&
    expect.same(3, past.firstQuorumCount) &&
    expect(!past.waitMore)
  }

  test("the last signer arriving inside the window finalizes immediately") {
    val first = evaluate(at(0), 3, canFinalize = true, fullCommitteeSigned = false, None, window)
    val full = evaluate(at(500), 4, canFinalize = true, fullCommitteeSigned = true, stampOf(first), window)
    expect(full.update == Clear) && expect(!full.waitMore)
  }
}
