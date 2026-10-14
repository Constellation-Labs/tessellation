package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.FunSuite

object LeaderScheduleSuite extends FunSuite {

  private def pid(i: Int): PeerId = PeerId(Hex(f"$i%02x" * 64))

  private val committee: List[PeerId] = (1 to 5).map(pid).toList

  test("a full view cycle visits every committee member exactly once") {
    val leaders = (0L until committee.size.toLong).map(v => LeaderSchedule.leader(committee, 1234L, v))
    expect.same(committee.toSet, leaders.toSet).and(expect.same(committee.size, leaders.distinct.size))
  }

  test("the leader does not depend on committee iteration order") {
    val shuffled = committee.reverse
    expect.same(LeaderSchedule.leader(committee, 77L, 3L), LeaderSchedule.leader(shuffled, 77L, 3L))
  }

  test("duplicate entries do not change the schedule") {
    expect.same(LeaderSchedule.leader(committee, 9L, 1L), LeaderSchedule.leader(committee ++ committee, 9L, 1L))
  }

  test("consecutive rounds at view 0 rotate through the committee") {
    val leaders = (100L until 105L).map(r => LeaderSchedule.leader(committee, r, 0L))
    expect.same(committee.size, leaders.distinct.size)
  }

  test("round and view advance the same schedule") {
    expect.same(LeaderSchedule.leader(committee, 10L, 2L), LeaderSchedule.leader(committee, 12L, 0L))
  }

  test("large and negative indices are handled without overflow") {
    val a = LeaderSchedule.leader(committee, Long.MaxValue, Long.MaxValue)
    val b = LeaderSchedule.leader(committee, -1L, 0L)
    expect(committee.contains(a), "Long.MaxValue inputs must index into the committee")
      .and(expect.same(committee.sorted.last, b))
  }

  test("an empty committee is rejected") {
    expect(scala.util.Try(LeaderSchedule.leader(Nil, 0L, 0L)).isFailure)
  }
}
