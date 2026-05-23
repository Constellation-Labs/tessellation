package io.constellationnetwork.node.shared.infrastructure.consensus.state

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.SimpleIOSuite

object StateTransitionsSuite extends SimpleIOSuite {

  private def pid(name: String): PeerId =
    PeerId(Hex(name.getBytes("UTF-8").map(b => f"$b%02x").mkString))

  pureTest("view-change leader pool uses Core when Core is populated") {
    val core = List(pid("core-1"), pid("core-2"))
    val nonCore = pid("non-core")
    val allFacilitators = core :+ nonCore

    val pool = StateTransitions.viewChangeLeaderPool(core, allFacilitators)

    expect.same(core, pool) &&
    expect(!pool.contains(nonCore))
  }

  pureTest("view-change leader pool falls back to facilitators when Core is empty") {
    val facilitators = List(pid("fac-1"), pid("fac-2"))

    expect.same(facilitators, StateTransitions.viewChangeLeaderPool(Nil, facilitators))
  }

  pureTest("ready promotion quorum requires an external corroborator for a two-peer recovery view") {
    expect.same(2, StateTransitions.readyPromotionQuorum(2, 2.0 / 3.0))
  }

  pureTest("ready promotion quorum follows supermajority for larger recovery views") {
    expect.same(4, StateTransitions.readyPromotionQuorum(5, 2.0 / 3.0))
  }

  pureTest("ready promotion external Ready floor requires the configured floor minus self") {
    expect.same(2, StateTransitions.readyPromotionExternalReadyFloor(3)) &&
    expect.same(3, StateTransitions.readyPromotionExternalReadyFloor(4))
  }
}
