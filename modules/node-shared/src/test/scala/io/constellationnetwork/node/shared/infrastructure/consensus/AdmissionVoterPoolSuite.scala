package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.node.shared.infrastructure.consensus.state.AdmissionVoterPool
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.FunSuite

object AdmissionVoterPoolSuite extends FunSuite {

  private def peer(c: Char): PeerId = PeerId(Hex(c.toString * 128))

  private val target = peer('1')
  private val committee = Set(peer('2'), peer('3'), peer('4'))

  test("admissions are voted by the frozen committee and the target never votes for itself") {
    expect.same(committee, AdmissionVoterPool.select(target, committee + target))
  }

  test("only committee members may emit admission votes") {
    expect(AdmissionVoterPool.allowsVoteEmission(peer('2'), committee)) &&
    expect(!AdmissionVoterPool.allowsVoteEmission(peer('5'), committee))
  }

  test("admission quorum matches the certified committee quorum in every supported mode") {
    val size = 6
    expect.same(4, AdmissionVoterPool.requiredQuorum(size, 2.0 / 3.0)) &&
    expect.same(CertifiedConsensus.requiredCoreQuorum(size, 2.0 / 3.0), AdmissionVoterPool.requiredQuorum(size, 2.0 / 3.0)) &&
    expect.same(size, AdmissionVoterPool.requiredQuorum(size, 1.0))
  }
}
