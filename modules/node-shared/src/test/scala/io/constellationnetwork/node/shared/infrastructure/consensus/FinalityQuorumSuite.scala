package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.node.shared.infrastructure.consensus.state._

import weaver.FunSuite

/** ADR-0021 finality floor: finalization requires a supermajority of the frozen round committee. */
object FinalityQuorumSuite extends FunSuite {

  private val supermajority: Double = 0.6666666666666666

  test("a 2-of-5 minority cannot reach the committee quorum") {
    val required = FinalityQuorum.of(5, supermajority)
    expect.same(4, required) && expect(2 < required)
  }

  test("unanimity requires the entire round committee") {
    expect.same(5, FinalityQuorum.of(5, 1.0))
  }

  test("the quorum is never below one") {
    expect.same(1, FinalityQuorum.of(0, supermajority)) && expect.same(1, FinalityQuorum.of(1, supermajority))
  }
}
