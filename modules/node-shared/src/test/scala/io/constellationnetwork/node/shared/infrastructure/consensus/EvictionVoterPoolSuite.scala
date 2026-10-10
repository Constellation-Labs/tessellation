package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.node.shared.infrastructure.consensus.state.EvictionVoterPool
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.FunSuite

object EvictionVoterPoolSuite extends FunSuite {

  private def peer(c: Char): PeerId = PeerId(Hex(c.toString * 128))

  test("evictions are voted by the frozen committee and the target never votes on its own eviction") {
    val target = peer('1')
    val committee = Set(peer('2'), peer('3'), peer('4'))
    expect.same(committee, EvictionVoterPool.select(target, committee + target))
  }
}
