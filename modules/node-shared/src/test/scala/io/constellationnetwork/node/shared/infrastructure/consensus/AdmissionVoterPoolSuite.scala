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
      AdmissionVoterPool.select(target, isProbationReadmission = false, requireCoreCertification = false, core, wider)
    )
  }

  test("legacy probation readmission preserves the wider witness recovery lane") {
    expect.same(
      core ++ tier1AndHistorical,
      AdmissionVoterPool.select(target, isProbationReadmission = true, requireCoreCertification = false, core, wider)
    )
  }

  test("certified atomic probation readmission requires Core certification") {
    expect.same(
      core,
      AdmissionVoterPool.select(target, isProbationReadmission = true, requireCoreCertification = true, core, wider)
    )
  }

  test("certified admission vote emission is Core-only while legacy emission remains unchanged") {
    val coreVoter = peer('2')
    val widerOnlyVoter = peer('5')

    expect(AdmissionVoterPool.allowsVoteEmission(coreVoter, requireCoreCertification = true, core)) &&
    expect(!AdmissionVoterPool.allowsVoteEmission(widerOnlyVoter, requireCoreCertification = true, core)) &&
    expect(AdmissionVoterPool.allowsVoteEmission(widerOnlyVoter, requireCoreCertification = false, core))
  }

  test("a wider-only probation quorum cannot satisfy certified atomic admission") {
    val quorum = 2
    val widerOnlyVotes = tier1AndHistorical
    val coreVotes = Set(peer('2'), peer('3'))
    val certifiedPool =
      AdmissionVoterPool.select(target, isProbationReadmission = true, requireCoreCertification = true, core, wider)

    expect(widerOnlyVotes.intersect(certifiedPool).size < quorum).and(expect(coreVotes.intersect(certifiedPool).size >= quorum))
  }

  test("certified admission quorum matches Core prepare quorum in every supported mode") {
    val coreSize = 6
    val supermajorityFraction = 2.0 / 3.0
    val unanimityFraction = 1.0

    expect.same(4, AdmissionVoterPool.requiredQuorum(coreSize, supermajorityFraction, requireCoreCertification = true)) &&
    expect.same(
      CertifiedConsensus.requiredCoreQuorum(coreSize, supermajorityFraction),
      AdmissionVoterPool.requiredQuorum(coreSize, supermajorityFraction, requireCoreCertification = true)
    ) &&
    expect.same(coreSize, AdmissionVoterPool.requiredQuorum(coreSize, unanimityFraction, requireCoreCertification = true)) &&
    expect.same(
      CertifiedConsensus.requiredCoreQuorum(coreSize, unanimityFraction),
      AdmissionVoterPool.requiredQuorum(coreSize, unanimityFraction, requireCoreCertification = true)
    )
  }
}
