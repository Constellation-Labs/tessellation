package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.effect.IO

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hex.Hex

import weaver.{FunSuite, SimpleIOSuite}

object HealthDerivedMembershipPolicySuite extends FunSuite {

  private def peer(index: Int): PeerId = PeerId(Hex(f"$index%0128x"))

  private val facilitators = List(peer(4), peer(1), peer(5), peer(2), peer(3))
  private val core = List(peer(4), peer(1), peer(5))

  test("GL0 atomic replacement capability does not re-enable automatic removal") {
    val policy = HealthDerivedMembershipPolicy.RetainSigningLeases

    expect(!policy.allowsAutomaticRemoval) &&
    expect(!policy.acceptsCertifiedNextRoundEvictions) &&
    expect(!policy.acceptsEvictionCertificates) &&
    expect(policy.acceptsEvictionVotes) &&
    expect(!policy.acceptsEvictionVotesAt(certifiedConsensusActive = false)) &&
    expect(policy.acceptsEvictionVotesAt(certifiedConsensusActive = true)) &&
    expect(!policy.allowsCertifiedAtomicReplacement(certifiedConsensusActive = false)) &&
    expect(policy.allowsCertifiedAtomicReplacement(certifiedConsensusActive = true))
  }

  test("GL0 retain policy preserves facilitator and Core ordering across a timeout") {
    val result = HealthDerivedMembershipPolicy.RetainSigningLeases.timeoutMembership(
      facilitators,
      core,
      roundStartFacilitators = facilitators,
      timeoutVoters = Set(peer(1), peer(2), peer(3)),
      shrinkFloor = 3
    )

    expect.same(facilitators, result.facilitators) &&
    expect.same(core, result.coreFacilitators) &&
    expect.same(core, result.leaderPool) &&
    expect.same(facilitators, result.evaluatedActive) &&
    expect(!result.shrinkApplied) &&
    expect(!result.shrinkEvaluated) &&
    expect.same(0, result.exclusionCount)
  }

  test("GL0 retain policy uses the frozen ordered committee when Core is empty") {
    val locallyActive = facilitators.tail
    val result = HealthDerivedMembershipPolicy.RetainSigningLeases.timeoutMembership(
      locallyActive,
      coreFacilitators = List.empty,
      roundStartFacilitators = facilitators,
      timeoutVoters = Set(peer(1), peer(2), peer(3)),
      shrinkFloor = 3
    )

    expect.same(facilitators, result.leaderPool) && expect.same(facilitators, result.facilitators)
  }

  test("node-local withdrawal converges GL0 view membership, leader pool, and signature hash input") {
    val withdrawnCore = peer(4)
    val activeAfterWithdrawal = facilitators.filterNot(_ == withdrawnCore)
    val result = HealthDerivedMembershipPolicy.RetainSigningLeases.timeoutMembership(
      activeAfterWithdrawal,
      core,
      roundStartFacilitators = facilitators,
      timeoutVoters = Set(peer(1), peer(2), peer(3)),
      shrinkFloor = 3
    )
    val fullActiveResult = HealthDerivedMembershipPolicy.RetainSigningLeases.timeoutMembership(
      facilitators,
      core,
      roundStartFacilitators = facilitators,
      timeoutVoters = Set(peer(1), peer(2), peer(3)),
      shrinkFloor = 3
    )
    val fullActiveCanonical = HealthDerivedMembershipPolicy.RetainSigningLeases.canonicalFacilitators(facilitators, facilitators)
    val withdrawnActiveCanonical =
      HealthDerivedMembershipPolicy.RetainSigningLeases.canonicalFacilitators(activeAfterWithdrawal, facilitators)

    expect.same(core, result.coreFacilitators) &&
    expect.same(facilitators, result.facilitators) &&
    expect.same(facilitators, result.evaluatedActive) &&
    expect.same(core, result.leaderPool) &&
    expect.same(fullActiveResult.facilitators, result.facilitators) &&
    expect.same(fullActiveResult.leaderPool, result.leaderPool) &&
    expect.same(facilitators, fullActiveCanonical) &&
    expect.same(fullActiveCanonical, withdrawnActiveCanonical) &&
    expect.same(
      core,
      HealthDerivedMembershipPolicy.RetainSigningLeases
        .certifiedViewChangeLeaderPool(core, activeAfterWithdrawal, facilitators)
    ) &&
    expect.same(
      HealthDerivedMembershipPolicy.RetainSigningLeases.certifiedViewChangeLeaderPool(core, facilitators, facilitators),
      HealthDerivedMembershipPolicy.RetainSigningLeases
        .certifiedViewChangeLeaderPool(core, activeAfterWithdrawal, facilitators)
    )
  }

  test("GL0 retain policy disables every automatic-removal authority") {
    val removals = Set(peer(1), peer(2))
    val policy = HealthDerivedMembershipPolicy.RetainSigningLeases

    expect.same(Set.empty, policy.persistentFacilityRemovals(removals)) &&
    expect.same(Set.empty, policy.acceptedEvictionTargets(removals)) &&
    expect(!policy.acceptsEvictionCertificates) &&
    expect(!policy.acceptsCertifiedNextRoundEvictions) &&
    expect(!policy.certifiedEvictionTargetsAllowed(removals)) &&
    expect(policy.certifiedEvictionTargetsAllowed(Set.empty)) &&
    expect(!policy.allowsAutomaticRemoval)
  }

  test("GL0 retain policy neutralizes health removals carried by an rc.6 anchor") {
    val carriedRemovedFacilitators = Set(peer(1), peer(2))
    val policy = HealthDerivedMembershipPolicy.RetainSigningLeases

    expect.same(Set.empty, policy.persistentFacilityRemovals(carriedRemovedFacilitators))
  }

  test("GL0 retain policy is stable across the v35 activation boundary") {
    val policy = HealthDerivedMembershipPolicy.RetainSigningLeases

    expect.same(policy, policy.forCertifiedView(certifiedConsensusActive = false)) &&
    expect.same(policy, policy.forCertifiedView(certifiedConsensusActive = true))
  }

}

object HealthDerivedMembershipConvergenceSuite extends SimpleIOSuite {

  private def peer(index: Int): PeerId = PeerId(Hex(f"$index%0128x"))

  private val roundStart = List(peer(4), peer(1), peer(5), peer(2), peer(3))
  private val core = List(peer(4), peer(1), peer(5))
  private val locallyWithdrawn = roundStart.filterNot(_ == peer(4))
  private val timeoutVoters = Set(peer(1), peer(2), peer(3))

  test("GL0 VCC and TC converge membership, leadership, and production facilitator hashes across local withdrawals") {
    val policy = HealthDerivedMembershipPolicy.RetainSigningLeases
    val vccFullRoster = policy.canonicalFacilitators(roundStart, roundStart)
    val vccWithdrawnRoster = policy.canonicalFacilitators(locallyWithdrawn, roundStart)
    val vccFullLeaderPool = policy.certifiedViewChangeLeaderPool(core, roundStart, roundStart)
    val vccWithdrawnLeaderPool = policy.certifiedViewChangeLeaderPool(core, locallyWithdrawn, roundStart)
    val tcFull = policy.timeoutMembership(roundStart, core, roundStart, timeoutVoters, shrinkFloor = 3)
    val tcWithdrawn = policy.timeoutMembership(locallyWithdrawn, core, roundStart, timeoutVoters, shrinkFloor = 3)

    JsonSerializer.forAsync[IO].flatMap { implicit jsonSerializer =>
      val hasher = Hasher.forJson[IO]

      for {
        vccFullHash <- hasher.hash(vccFullRoster)
        vccWithdrawnHash <- hasher.hash(vccWithdrawnRoster)
        tcFullHash <- hasher.hash(tcFull.facilitators)
        tcWithdrawnHash <- hasher.hash(tcWithdrawn.facilitators)
      } yield
        expect.same(roundStart, vccFullRoster) &&
          expect.same(vccFullRoster, vccWithdrawnRoster) &&
          expect.same(vccFullLeaderPool, vccWithdrawnLeaderPool) &&
          expect.same(roundStart, tcFull.facilitators) &&
          expect.same(tcFull.facilitators, tcWithdrawn.facilitators) &&
          expect.same(vccFullHash, vccWithdrawnHash) &&
          expect.same(tcFullHash, tcWithdrawnHash) &&
          expect.same(vccFullHash, tcFullHash)
    }
  }

}
