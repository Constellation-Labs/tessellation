package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.node.shared.infrastructure.consensus.state.AdmissionVoterPool
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.FunSuite

object AdmissionVoterPoolSuite extends FunSuite {

  private def peer(c: Char): PeerId = PeerId(Hex(c.toString * 128))

  private val target = peer('1')
  private val core = Set(peer('2'), peer('3'), peer('4'))
  private val tier1AndHistorical = Set(peer('5'), peer('6'))
  private val wider = core ++ tier1AndHistorical + target

  test("open expansion is certified by Core only") {
    expect.same(
      core,
      AdmissionVoterPool.select(target, isProbationReadmission = false, core, wider)
    )
  }

  test("probation readmission preserves the wider witness recovery lane") {
    expect.same(
      core ++ tier1AndHistorical,
      AdmissionVoterPool.select(target, isProbationReadmission = true, core, wider)
    )
  }
}
