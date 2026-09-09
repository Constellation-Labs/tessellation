package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.node.shared.infrastructure.consensus.state.EvictionVoterPool
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.FunSuite

object EvictionVoterPoolSuite extends FunSuite {

  private def peer(c: Char): PeerId = PeerId(Hex(c.toString * 128))

  private val target = peer('1')
  private val core = Set(peer('2'), peer('3'), peer('4'))
  private val tier1AndHistorical = Set(peer('5'), peer('6'))
  private val wider = core ++ tier1AndHistorical + target

  test("Tier-1 eviction is certified by Core only") {
    expect.same(
      core,
      EvictionVoterPool.select(target, isTier1Target = true, core, wider)
    )
  }

  test("Core-target stall eviction preserves the wider witness recovery lane") {
    expect.same(
      core ++ tier1AndHistorical,
      EvictionVoterPool.select(target, isTier1Target = false, core, wider)
    )
  }
}
