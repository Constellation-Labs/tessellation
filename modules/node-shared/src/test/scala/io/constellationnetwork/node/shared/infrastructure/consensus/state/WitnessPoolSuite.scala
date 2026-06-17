package io.constellationnetwork.node.shared.infrastructure.consensus.state

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.FunSuite

object WitnessPoolSuite extends FunSuite {

  private def pid(tag: String): PeerId =
    PeerId(Hex(tag.getBytes("UTF-8").map(b => f"$b%02x").mkString.padTo(64, '0')))

  private val p1 = pid("p1")
  private val p2 = pid("p2")
  private val p3 = pid("p3")
  private val p4 = pid("p4")
  private val p5 = pid("p5")

  test("forTarget: empty peerQuality reduces to (eligible - target)") {
    val result = WitnessPool.forTarget(
      eligibleFacilitators = Set(p1, p2, p3),
      peerQuality = Map.empty,
      minParticipationObservations = 5,
      target = p2
    )
    expect.eql(Set(p1, p3), result)
  }

  test("forTarget: historical participants below minObservations are excluded") {
    val result = WitnessPool.forTarget(
      eligibleFacilitators = Set(p1),
      peerQuality = Map(
        p2 -> ((10, 4)), // below floor of 5
        p3 -> ((10, 5)), // at floor: included
        p4 -> ((0, 100)) // above floor: included
      ),
      minParticipationObservations = 5,
      target = p3
    )
    // p1 from eligible, p4 from peerQuality; p2 below floor; p3 removed as target.
    expect.eql(Set(p1, p4), result)
  }

  test("forTarget: target is removed even if present in both eligible and peerQuality") {
    val result = WitnessPool.forTarget(
      eligibleFacilitators = Set(p1, p2),
      peerQuality = Map(p2 -> ((50, 50)), p3 -> ((50, 50))),
      minParticipationObservations = 5,
      target = p2
    )
    expect.eql(Set(p1, p3), result)
  }

  test("all: union of eligible and historical-participants, no target removal") {
    val result = WitnessPool.all(
      eligibleFacilitators = Set(p1, p2),
      peerQuality = Map(
        p2 -> ((1, 5)),
        p3 -> ((1, 10)),
        p4 -> ((1, 4)) // below floor: excluded
      ),
      minParticipationObservations = 5
    )
    expect.eql(Set(p1, p2, p3), result)
  }

  test("forTarget: pool is order-independent (Set semantics)") {
    val a = WitnessPool.forTarget(Set(p1, p2, p3), Map(p4 -> ((0, 7)), p5 -> ((0, 8))), 5, p1)
    val b = WitnessPool.forTarget(Set(p3, p2, p1), Map(p5 -> ((0, 8)), p4 -> ((0, 7))), 5, p1)
    expect.eql(a, b)
  }
}
