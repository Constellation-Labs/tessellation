package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.FunSuite

object OpenAdmissionPolicySuite extends FunSuite {

  private def peer(c: Char): PeerId = PeerId(Hex(c.toString * 128))
  private def peer(index: Int): PeerId = PeerId(Hex(f"$index%0128x"))

  private val committee = Set(peer('1'), peer('2'), peer('3'), peer('4'), peer('5'), peer('6'))

  test("next-seat headroom uses the exact finality floor for committee size plus one") {
    val fourObserved = OpenAdmissionPolicy.evaluate(
      cadenceAllowed = true,
      currentCommittee = committee,
      locallyObservedParentSigners = Some(committee.take(4)),
      quorumThresholdFraction = 2.0 / 3.0,
      headroomGateActive = true
    )
    val fiveObserved = OpenAdmissionPolicy.evaluate(
      cadenceAllowed = true,
      currentCommittee = committee,
      locallyObservedParentSigners = Some(committee.take(5)),
      quorumThresholdFraction = 2.0 / 3.0,
      headroomGateActive = true
    )

    expect.same(Some(7), fourObserved.headroom.map(_.nextCommitteeSize)) &&
    expect.same(Some(5), fourObserved.headroom.map(_.nextFinalityFloor)) &&
    expect.same(Some(-1), fourObserved.headroom.map(_.margin)) &&
    expect(!fourObserved.allowsOpenAdmission) &&
    expect.same(Some(0), fiveObserved.headroom.map(_.margin)) &&
    expect(fiveObserved.allowsOpenAdmission)
  }

  test("proofs from outside the current committee do not create admission headroom") {
    val outsiders = Set(peer('a'), peer('b'), peer('c'))
    val decision = OpenAdmissionPolicy.evaluate(
      cadenceAllowed = true,
      currentCommittee = committee,
      locallyObservedParentSigners = Some(committee.take(4) ++ outsiders),
      quorumThresholdFraction = 2.0 / 3.0,
      headroomGateActive = true
    )

    expect.same(Some(4), decision.headroom.map(_.observedCurrentCommitteeSigners)) &&
    expect(!decision.allowsOpenAdmission)
  }

  test("Tier-1 silence eviction is suppressed while the signer set supports one more seat") {
    val headroom = FinalityHeadroom.evaluate(
      currentCommittee = committee,
      locallyObservedParentSigners = committee.take(5),
      quorumThresholdFraction = 2.0 / 3.0
    )

    expect.same(6, headroom.currentCommitteeSize) &&
    expect.same(5, headroom.observedCurrentCommitteeSigners) &&
    expect.same(4, headroom.currentFinalityFloor) &&
    expect.same(5, headroom.nextFinalityFloor) &&
    expect.same(1, headroom.currentMargin) &&
    expect(headroom.allowsExpansion) &&
    expect(!headroom.allowsSilentEviction) &&
    expect(!headroom.holdsMembership)
  }

  test("current-floor to next-floor gap is a neutral membership dead band") {
    val headroom = FinalityHeadroom.evaluate(
      currentCommittee = committee,
      locallyObservedParentSigners = committee.take(4),
      quorumThresholdFraction = 2.0 / 3.0
    )

    expect.same(-1, headroom.margin) &&
    expect.same(0, headroom.currentMargin) &&
    expect(!headroom.allowsExpansion) &&
    expect(!headroom.allowsSilentEviction) &&
    expect(headroom.holdsMembership)
  }

  test("Tier-1 silence eviction requires a deficit against the current committee floor") {
    val headroom = FinalityHeadroom.evaluate(
      currentCommittee = committee,
      locallyObservedParentSigners = committee.take(3),
      quorumThresholdFraction = 2.0 / 3.0
    )

    expect.same(-1, headroom.currentMargin) &&
    expect(!headroom.allowsExpansion) &&
    expect(headroom.allowsSilentEviction) &&
    expect(!headroom.holdsMembership)
  }

  test("a floor step holds the admitted seat instead of oscillating membership") {
    val peers = (1 to 21).map(peer).toList
    val signers = peers.take(14).toSet
    val beforeAdmission = FinalityHeadroom.evaluate(peers.take(20).toSet, signers, 2.0 / 3.0)
    val afterAdmission = FinalityHeadroom.evaluate(peers.toSet, signers, 2.0 / 3.0)

    expect.same(14, beforeAdmission.currentFinalityFloor) &&
    expect.same(14, beforeAdmission.nextFinalityFloor) &&
    expect(beforeAdmission.allowsExpansion) &&
    expect.same(14, afterAdmission.currentFinalityFloor) &&
    expect.same(15, afterAdmission.nextFinalityFloor) &&
    expect(afterAdmission.holdsMembership) &&
    expect(!afterAdmission.allowsSilentEviction)
  }

  test("expand, hold, and evict form an exact disjoint policy for every observed signer count") {
    val decisions = (1 to 100).flatMap { size =>
      val peers = (1 to size).map(peer).toList
      (0 to size).map { signerCount =>
        FinalityHeadroom.evaluate(peers.toSet, peers.take(signerCount).toSet, 2.0 / 3.0)
      }
    }

    expect(decisions.forall { decision =>
      List(decision.allowsExpansion, decision.holdsMembership, decision.allowsSilentEviction).count(identity) == 1
    })
  }

  test("off-cadence rounds suppress open admission even with sufficient headroom") {
    val decision = OpenAdmissionPolicy.evaluate(
      cadenceAllowed = false,
      currentCommittee = committee,
      locallyObservedParentSigners = Some(committee),
      quorumThresholdFraction = 2.0 / 3.0,
      headroomGateActive = true
    )

    expect(decision.headroom.exists(_.allowsExpansion)) && expect(!decision.allowsOpenAdmission)
  }

  test("layers without local proof headroom retain cadence-only behavior") {
    val allowed = OpenAdmissionPolicy.evaluate(
      cadenceAllowed = true,
      currentCommittee = committee,
      locallyObservedParentSigners = None,
      quorumThresholdFraction = 1.0,
      headroomGateActive = true
    )
    val suppressed = allowed.copy(cadenceAllowed = false)

    expect.same(None, allowed.headroom) && expect(allowed.allowsOpenAdmission) && expect(!suppressed.allowsOpenAdmission)
  }

  test("bootstrap bypasses the post-bootstrap next-seat floor so a singleton can grow under unanimity") {
    val singleton = Set(peer('1'))
    val inBootstrap = OpenAdmissionPolicy.evaluate(
      cadenceAllowed = true,
      currentCommittee = singleton,
      locallyObservedParentSigners = Some(singleton),
      quorumThresholdFraction = 1.0,
      headroomGateActive = false
    )
    val postBootstrap = OpenAdmissionPolicy.evaluate(
      cadenceAllowed = true,
      currentCommittee = singleton,
      locallyObservedParentSigners = Some(singleton),
      quorumThresholdFraction = 1.0,
      headroomGateActive = true
    )

    expect.same(None, inBootstrap.headroom) &&
    expect(inBootstrap.allowsOpenAdmission) &&
    expect.same(Some(2), postBootstrap.headroom.map(_.nextFinalityFloor)) &&
    expect(!postBootstrap.allowsOpenAdmission)
  }

  test("off-cadence certificate filtering suppresses open expansion but retains probation recovery") {
    val probationTarget = peer('a')
    val openTarget = peer('b')

    expect(OpenAdmissionPolicy.certificateAllowed(probationTarget, Set(probationTarget), openCadenceAllowed = false)) &&
    expect(!OpenAdmissionPolicy.certificateAllowed(openTarget, Set(probationTarget), openCadenceAllowed = false)) &&
    expect(OpenAdmissionPolicy.certificateAllowed(openTarget, Set(probationTarget), openCadenceAllowed = true))
  }

  test("active penalties block open certificates but not consensus-agreed probation recovery") {
    val probationTarget = peer('a')
    val openTarget = peer('b')
    val activePenaltyPeers = Set(probationTarget, openTarget)

    expect(!OpenAdmissionPolicy.penaltyBlocksCertificate(probationTarget, Set(probationTarget), activePenaltyPeers)) &&
    expect(OpenAdmissionPolicy.penaltyBlocksCertificate(openTarget, Set(probationTarget), activePenaltyPeers)) &&
    expect(!OpenAdmissionPolicy.penaltyBlocksCertificate(peer('c'), Set(probationTarget), activePenaltyPeers))
  }
}
