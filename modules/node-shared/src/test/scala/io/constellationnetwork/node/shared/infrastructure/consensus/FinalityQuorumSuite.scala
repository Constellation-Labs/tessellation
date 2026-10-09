package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.FunSuite

/** ADR-0021 finality floor: finalization requires a supermajority of the frozen round committee, never only of a shrunken Core. */
object FinalityQuorumSuite extends FunSuite {

  private val supermajority: Double = 0.6666666666666666

  private def pid(tag: String): PeerId = PeerId(Hex(tag * 32))

  private val n1 = pid("01")
  private val n2 = pid("02")
  private val n3 = pid("03")
  private val n4 = pid("04")
  private val n5 = pid("05")
  private val committee5: Set[PeerId] = Set(n1, n2, n3, n4, n5)

  test("a 2-of-5 minority Core cannot finalize under the committee floor") {
    val required = FinalityQuorum.required(coreSize = 2, roundStartFacilitatorsSize = 5, applyClusterFloor = true, supermajority)

    expect.same(4, required) &&
    expect(2 < required, "a 2-signer minority must stay below the committee floor") &&
    expect(4 >= required, "a committee super-majority (4 of 5) must finalize")
  }

  test("unanimity requires the entire round committee") {
    expect.same(5, FinalityQuorum.required(coreSize = 2, roundStartFacilitatorsSize = 5, applyClusterFloor = true, 1.0))
  }

  test("a healthy committee (Core == committee) is unchanged by the floor") {
    val floored = FinalityQuorum.required(coreSize = 5, roundStartFacilitatorsSize = 5, applyClusterFloor = true, supermajority)
    val unfloored = FinalityQuorum.required(coreSize = 5, roundStartFacilitatorsSize = 5, applyClusterFloor = false, supermajority)

    expect.same(unfloored, floored) && expect.same(4, floored)
  }

  test("without the floor the liveness quorum is Core-sized") {
    expect.same(2, FinalityQuorum.required(coreSize = 2, roundStartFacilitatorsSize = 5, applyClusterFloor = false, supermajority)) &&
    expect.same(2, FinalityQuorum.coreQuorum(2, supermajority)) &&
    expect.same(1, FinalityQuorum.coreQuorum(0, supermajority))
  }

  test("collection universe includes the frozen committee when the floor is active (no mid-round-eviction deadlock)") {
    val active = Set(n1, n2, n3)
    val universe = ConsensusStateAdvancer.collectionUniverse(active, committee5, floorActive = true)

    expect(committee5.subsetOf(universe), s"frozen committee must remain in the lookup universe, got $universe") &&
    expect(universe.contains(n4) && universe.contains(n5), "frozen members dropped from the active set must still be looked up")
  }

  test("collection universe is exactly the active set when the floor is off") {
    val active = Set(n1, n2, n3)
    expect.same(active, ConsensusStateAdvancer.collectionUniverse(active, Set(n1, n2), floorActive = false))
  }
}
