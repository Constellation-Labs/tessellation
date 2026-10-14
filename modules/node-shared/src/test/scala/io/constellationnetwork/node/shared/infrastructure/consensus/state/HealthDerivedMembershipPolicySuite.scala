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
  test("GL0 atomic replacement capability does not re-enable automatic removal") {
    val policy = HealthDerivedMembershipPolicy.RetainSigningLeases

    expect(!policy.allowsAutomaticRemoval) &&
    expect(!policy.acceptsCertifiedNextRoundEvictions) &&
    expect(!policy.acceptsEvictionCertificates) &&
    expect(policy.acceptsEvictionVotes) &&
    expect(!policy.acceptsEvictionVotesForRound(roundExists = false)) &&
    expect(policy.acceptsEvictionVotesForRound(roundExists = true)) &&
    expect(policy.allowsCertifiedAtomicReplacement)
  }

  test("GL0 retain policy keeps the frozen committee as both membership and leader pool across a timeout") {
    val result = HealthDerivedMembershipPolicy.RetainSigningLeases.timeoutMembership(
      facilitators,
      roundStartFacilitators = facilitators,
      timeoutVoters = Set(peer(1), peer(2), peer(3)),
      shrinkFloor = 3
    )

    expect.same(facilitators, result.facilitators) &&
    expect.same(facilitators, result.leaderPool) &&
    expect.same(facilitators, result.evaluatedActive) &&
    expect(!result.shrinkApplied) &&
    expect(!result.shrinkEvaluated) &&
    expect.same(0, result.exclusionCount)
  }

  test("node-local withdrawal converges GL0 view membership, leader pool, and signature hash input") {
    val withdrawn = peer(4)
    val activeAfterWithdrawal = facilitators.filterNot(_ == withdrawn)
    val result = HealthDerivedMembershipPolicy.RetainSigningLeases.timeoutMembership(
      activeAfterWithdrawal,
      roundStartFacilitators = facilitators,
      timeoutVoters = Set(peer(1), peer(2), peer(3)),
      shrinkFloor = 3
    )
    val fullActiveResult = HealthDerivedMembershipPolicy.RetainSigningLeases.timeoutMembership(
      facilitators,
      roundStartFacilitators = facilitators,
      timeoutVoters = Set(peer(1), peer(2), peer(3)),
      shrinkFloor = 3
    )
    val fullActiveCanonical = HealthDerivedMembershipPolicy.RetainSigningLeases.canonicalFacilitators(facilitators, facilitators)
    val withdrawnActiveCanonical =
      HealthDerivedMembershipPolicy.RetainSigningLeases.canonicalFacilitators(activeAfterWithdrawal, facilitators)

    expect.same(facilitators, result.facilitators) &&
    expect.same(facilitators, result.evaluatedActive) &&
    expect.same(facilitators, result.leaderPool) &&
    expect.same(fullActiveResult.facilitators, result.facilitators) &&
    expect.same(fullActiveResult.leaderPool, result.leaderPool) &&
    expect.same(facilitators, fullActiveCanonical) &&
    expect.same(fullActiveCanonical, withdrawnActiveCanonical) &&
    expect.same(facilitators, HealthDerivedMembershipPolicy.RetainSigningLeases.certifiedViewChangeLeaderPool(facilitators))
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

}

object HealthDerivedMembershipConvergenceSuite extends SimpleIOSuite {

  private def peer(index: Int): PeerId = PeerId(Hex(f"$index%0128x"))

  private val roundStart = List(peer(4), peer(1), peer(5), peer(2), peer(3))
  private val locallyWithdrawn = roundStart.filterNot(_ == peer(4))
  private val timeoutVoters = Set(peer(1), peer(2), peer(3))

  test("GL0 VCC and TC converge membership, leadership, and production facilitator hashes across local withdrawals") {
    val policy = HealthDerivedMembershipPolicy.RetainSigningLeases
    val vccFullRoster = policy.canonicalFacilitators(roundStart, roundStart)
    val vccWithdrawnRoster = policy.canonicalFacilitators(locallyWithdrawn, roundStart)
    val vccFullLeaderPool = policy.certifiedViewChangeLeaderPool(roundStart)
    val vccWithdrawnLeaderPool = policy.certifiedViewChangeLeaderPool(roundStart)
    val tcFull = policy.timeoutMembership(roundStart, roundStart, timeoutVoters, shrinkFloor = 3)
    val tcWithdrawn = policy.timeoutMembership(locallyWithdrawn, roundStart, timeoutVoters, shrinkFloor = 3)

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
