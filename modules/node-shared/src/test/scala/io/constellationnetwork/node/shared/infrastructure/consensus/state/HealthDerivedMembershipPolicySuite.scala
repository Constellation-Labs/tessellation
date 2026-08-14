package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.effect.IO

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.infrastructure.consensus.ActiveFacilitatorAdmission
import io.constellationnetwork.schema.SnapshotOrdinal
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

  test("Currency legacy policy reproduces certified timeout shrink and selected ordering") {
    val voters = Set(peer(1), peer(2), peer(3))
    val expected = facilitators.filter(voters.contains)
    val result = HealthDerivedMembershipPolicy.LegacyAutomaticRemoval.timeoutMembership(
      facilitators,
      core,
      roundStartFacilitators = facilitators,
      timeoutVoters = voters,
      shrinkFloor = 3
    )

    expect(result.shrinkApplied) &&
    expect(result.shrinkEvaluated) &&
    expect.same(expected, result.facilitators) &&
    expect.same(expected, result.coreFacilitators) &&
    expect.same(expected, result.leaderPool) &&
    expect.same(2, result.exclusionCount)
  }

  test("Currency legacy no-shrink keeps membership but selects the leader from full active") {
    val result = HealthDerivedMembershipPolicy.LegacyAutomaticRemoval.timeoutMembership(
      facilitators,
      core,
      roundStartFacilitators = facilitators,
      timeoutVoters = Set(peer(1), peer(2)),
      shrinkFloor = 3
    )

    expect(!result.shrinkApplied) &&
    expect(result.shrinkEvaluated) &&
    expect.same(facilitators, result.facilitators) &&
    expect.same(core, result.coreFacilitators) &&
    expect.same(facilitators, result.leaderPool)
  }

  test("Currency legacy policy retains automatic-removal authority") {
    val removals = Set(peer(1), peer(2))
    val policy = HealthDerivedMembershipPolicy.LegacyAutomaticRemoval

    expect.same(removals, policy.persistentFacilityRemovals(removals)) &&
    expect.same(removals, policy.acceptedEvictionTargets(removals)) &&
    expect.same(facilitators.tail, policy.canonicalFacilitators(facilitators.tail, facilitators)) &&
    expect(policy.acceptsEvictionCertificates) &&
    expect(policy.acceptsCertifiedNextRoundEvictions) &&
    expect(policy.acceptsEvictionVotesAt(certifiedConsensusActive = false)) &&
    expect(policy.acceptsEvictionVotesAt(certifiedConsensusActive = true)) &&
    expect(policy.certifiedEvictionTargetsAllowed(removals)) &&
    expect(policy.allowsAutomaticRemoval)
  }

  test("v35 freezes certified view membership without removing Currency next-round eviction authority") {
    val layerPolicy = HealthDerivedMembershipPolicy.LegacyAutomaticRemoval
    val legacyViewPolicy = layerPolicy.forCertifiedView(certifiedConsensusActive = false)
    val certifiedViewPolicy = layerPolicy.forCertifiedView(certifiedConsensusActive = true)
    val locallyWithdrawn = facilitators.tail
    val timeout = certifiedViewPolicy.timeoutMembership(
      facilitators = locallyWithdrawn,
      coreFacilitators = core,
      roundStartFacilitators = facilitators,
      timeoutVoters = Set(peer(1), peer(2), peer(3)),
      shrinkFloor = 3
    )
    val vccRoster = certifiedViewPolicy.canonicalFacilitators(locallyWithdrawn, facilitators)
    val vccLeaderPool = certifiedViewPolicy.certifiedViewChangeLeaderPool(core, locallyWithdrawn, facilitators)

    expect.same(layerPolicy, legacyViewPolicy) &&
    expect.same(HealthDerivedMembershipPolicy.RetainSigningLeases, certifiedViewPolicy) &&
    expect.same(facilitators, timeout.facilitators) &&
    expect.same(core, timeout.coreFacilitators) &&
    expect.same(core, timeout.leaderPool) &&
    expect(!timeout.shrinkApplied) &&
    expect.same(facilitators, vccRoster) &&
    expect.same(core, vccLeaderPool) &&
    expect(layerPolicy.acceptsEvictionCertificates) &&
    expect(!certifiedViewPolicy.acceptsEvictionCertificates)
  }

  test("GL0 retain policy is stable across the v35 activation boundary") {
    val policy = HealthDerivedMembershipPolicy.RetainSigningLeases

    expect.same(policy, policy.forCertifiedView(certifiedConsensusActive = false)) &&
    expect.same(policy, policy.forCertifiedView(certifiedConsensusActive = true))
  }

  test("Currency legacy certified view-change leader pool matches rc6") {
    val policy = HealthDerivedMembershipPolicy.LegacyAutomaticRemoval

    expect.same(core, policy.certifiedViewChangeLeaderPool(core, facilitators, facilitators)) &&
    expect.same(facilitators, policy.certifiedViewChangeLeaderPool(List.empty, facilitators, facilitators))
  }

  test("Currency legacy timeout policy matches the rc6 formula across voter sets and floors") {
    val cases = for {
      voterMask <- 0 until (1 << facilitators.size)
      floor <- 1 to (facilitators.size + 1)
    } yield {
      val voters = facilitators.zipWithIndex.collect {
        case (pid, index) if (voterMask & (1 << index)) != 0 => pid
      }.toSet
      val legacy = ActiveFacilitatorAdmission.fromCertifiedTimeout(
        selected = facilitators,
        recentSigners = SortedMap.empty[SnapshotOrdinal, SortedSet[PeerId]],
        timeoutVoters = voters,
        minActiveSize = floor
      )
      val actual = HealthDerivedMembershipPolicy.LegacyAutomaticRemoval.timeoutMembership(
        facilitators,
        core,
        roundStartFacilitators = facilitators,
        timeoutVoters = voters,
        shrinkFloor = floor
      )
      val expectedFacilitators = if (legacy.recentFilterApplied) legacy.active else facilitators
      val expectedCore = if (legacy.recentFilterApplied) legacy.active else core

      expect.same(expectedFacilitators, actual.facilitators) &&
      expect.same(expectedCore, actual.coreFacilitators) &&
      expect.same(legacy.active, actual.leaderPool) &&
      expect.same(legacy.recentFilterApplied, actual.shrinkApplied) &&
      expect.same(legacy.exclusions.size, actual.exclusionCount)
    }

    cases.reduce(_ && _)
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

  pureTest("Currency legacy policy preserves mutable active membership at VCC and no-shrink TC boundaries") {
    val policy = HealthDerivedMembershipPolicy.LegacyAutomaticRemoval
    val vccRoster = policy.canonicalFacilitators(locallyWithdrawn, roundStart)
    val vccLeaderPool = policy.certifiedViewChangeLeaderPool(core, locallyWithdrawn, roundStart)
    val tc = policy.timeoutMembership(
      locallyWithdrawn,
      core,
      roundStart,
      timeoutVoters = Set(peer(1), peer(2)),
      shrinkFloor = 3
    )

    expect.same(locallyWithdrawn, vccRoster) &&
    expect.same(core, vccLeaderPool) &&
    expect.same(locallyWithdrawn, tc.facilitators) &&
    expect.same(core, tc.coreFacilitators) &&
    expect(!tc.shrinkApplied)
  }
}
