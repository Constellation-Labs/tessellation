package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.node.shared.infrastructure.consensus.MembershipClearing._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{ClearingState, SnapshotOrdinal}
import io.constellationnetwork.security.hex.Hex

import weaver.FunSuite

object MembershipClearingSuite extends FunSuite {

  private val params = Params(intervalLength = 4L, requiredLowIntervals = 3)

  private def peer(c: Char): PeerId = PeerId(Hex(c.toString * 128))
  private def ord(n: Long): SnapshotOrdinal = SnapshotOrdinal.unsafeApply(n)

  private val a = peer('a')
  private val b = peer('b')
  private val c = peer('c')
  private val d = peer('d')
  private val committee = Set(a, b, c, d)

  /** Record rounds `from` until `until` (exclusive), as derived by each child round. */
  private def run(
    state: ClearingState,
    from: Long,
    until: Long,
    members: Long => Set[PeerId] = _ => committee,
    signers: Long => Set[PeerId]
  ): ClearingState =
    (from until until).foldLeft(state) { (acc, n) =>
      recordRound(acc, ord(n), members(n), signers(n), ord(n + 1), params)
    }

  private val allSign: Long => Set[PeerId] = _ => committee
  private val dSilent: Long => Set[PeerId] = _ => committee - d

  test("a fully seated, fully present interval counts and keeps the streak at zero") {
    val state = run(ClearingState.empty, 0L, 4L, signers = allSign)
    val record = state.members(a)
    expect.same(Some(0L), record.lastCountedInterval) &&
    expect.same(4, record.lastCountedSeated) &&
    expect.same(4, record.lastCountedPresent) &&
    expect.same(0, record.lowStreak) &&
    expect.same(1L, record.currentInterval) &&
    expect.same(0, record.seated)
  }

  test("an interval closes when the deriving round starts the next interval, not later") {
    val beforeBoundary = run(ClearingState.empty, 0L, 3L, signers = dSilent)
    val atBoundary = run(beforeBoundary, 3L, 4L, signers = dSilent)
    expect.same(None, beforeBoundary.members(d).lastCountedInterval) &&
    expect.same(Some(0L), atBoundary.members(d).lastCountedInterval) &&
    expect.same(1, atBoundary.members(d).lowStreak)
  }

  test("a partial interval is never counted") {
    // Activation (or a recovery root) mid-interval: only rounds 2 and 3 of interval 0 are recorded.
    val state = run(ClearingState.empty, 2L, 4L, signers = dSilent)
    expect.same(None, state.members(d).lastCountedInterval) && expect.same(0, state.members(d).lowStreak)
  }

  test("presence below one tenth extends the streak; one tenth or more resets it") {
    val p = Params(intervalLength = 10L, requiredLowIntervals = 3)
    def ten(state: ClearingState, start: Long, presentRounds: Int) =
      (start until start + 10L).foldLeft(state) { (acc, n) =>
        val signers = if (n - start < presentRounds) committee else committee - d
        recordRound(acc, ord(n), committee, signers, ord(n + 1), p)
      }
    val low = ten(ClearingState.empty, 0L, 0)
    val stillLow = ten(low, 10L, 0)
    val exactlyTenth = ten(stillLow, 20L, 1)
    expect.same(1, low.members(d).lowStreak) &&
    expect.same(2, stillLow.members(d).lowStreak) &&
    expect.same(0, exactlyTenth.members(d).lowStreak)
  }

  test("three consecutive low intervals make a member pending and selectable") {
    val state = run(ClearingState.empty, 0L, 12L, signers = dSilent)
    expect.same(3, state.members(d).lowStreak) &&
    expect.same(List(d), pending(state, params).map(_._1)) &&
    expect.same(Some(d), selectTarget(state, 3L, params))
  }

  test("two low intervals are not enough") {
    val state = run(ClearingState.empty, 0L, 8L, signers = dSilent)
    expect.same(2, state.members(d).lowStreak) && expect.same(None, selectTarget(state, 2L, params))
  }

  test("at most one clearing per interval") {
    val state = run(ClearingState.empty, 0L, 12L, signers = _ => Set(a, b))
    val first = selectTarget(state, 3L, params)
    val afterClearing = markCleared(state, first.get, 3L)
    expect.same(Some(c), first) &&
    expect.same(None, selectTarget(afterClearing, 3L, params)) &&
    expect.same(Some(d), selectTarget(afterClearing, 4L, params))
  }

  test("the lowest presence ratio is cleared first, then the lowest PeerId") {
    val p = Params(intervalLength = 20L, requiredLowIntervals = 1)
    // In one counted interval: c present 1 of 20, d present 0 of 20; both below a tenth.
    val state = (0L until 20L).foldLeft(ClearingState.empty) { (acc, n) =>
      val signers = if (n == 0L) Set(a, b, c) else Set(a, b)
      recordRound(acc, ord(n), committee, signers, ord(n + 1), p)
    }
    val tie = (0L until 20L).foldLeft(ClearingState.empty) { (acc, n) =>
      recordRound(acc, ord(n), committee, Set(a, b), ord(n + 1), p)
    }
    expect.same(Some(d), selectTarget(state, 1L, p)) && expect.same(Some(c), selectTarget(tie, 1L, p))
  }

  test("leaving the committee drops the record and re-admission starts a fresh one") {
    val low = run(ClearingState.empty, 0L, 8L, signers = dSilent)
    val departed = run(low, 8L, 9L, members = _ => Set(a, b, c), signers = _ => Set(a, b, c))
    val readmitted = run(departed, 9L, 10L, signers = allSign)
    expect.same(2, low.members(d).lowStreak) &&
    expect(!departed.members.contains(d)) &&
    expect.same(0, readmitted.members(d).lowStreak) &&
    expect.same(ord(9L), readmitted.members(d).seatActivationOrdinal) &&
    expect.same(None, readmitted.members(d).lastCountedInterval)
  }

  test("a mid-interval admission is not counted until its first full interval") {
    val joined = run(ClearingState.empty, 0L, 12L, members = n => if (n < 2L) committee - d else committee, signers = dSilent)
    // d seated from round 2: interval 0 is partial, intervals 1 and 2 count.
    expect.same(2, joined.members(d).lowStreak)
  }

  test("retainSeated drops exits and evictions, and keeps the cleared-interval marker") {
    val state = markCleared(run(ClearingState.empty, 0L, 4L, signers = allSign), a, 0L)
    val retained = retainSeated(state, Set(b, c))
    expect.same(Set(b, c), retained.members.keySet) && expect.same(Some(0L), retained.clearedInterval)
  }

  test("a gap in recorded rounds leaves the gapped interval uncounted") {
    val beforeGap = run(ClearingState.empty, 0L, 4L, signers = dSilent)
    // Rounds 4..7 were never recorded (for example a recovery root); recording resumes at 8.
    val resumed = run(beforeGap, 8L, 12L, signers = dSilent)
    expect.same(2, resumed.members(d).lowStreak) && expect.same(Some(2L), resumed.members(d).lastCountedInterval)
  }

  test("default parameters match the design") {
    expect.same(2048L, IntervalLength) && expect.same(3, RequiredLowIntervals) && expect.same(2048L, CooldownOrdinals)
  }
}
