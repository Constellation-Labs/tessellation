package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.concurrent.duration._

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import weaver.FunSuite

object OpenAdmissionPolicySuite extends FunSuite {

  private def peer(c: Char): PeerId = PeerId(Hex(c.toString * 128))
  private def peer(index: Int): PeerId = PeerId(Hex(f"$index%0128x"))
  private def proofHistory(rounds: List[Set[PeerId]]): AdmissionProofHistory.History =
    rounds.zipWithIndex.foldLeft(AdmissionProofHistory.History.empty) {
      case (history, (signers, index)) =>
        AdmissionProofHistory.observe(history, index.toLong + 1L, Hash.fromBytes(s"parent-$index".getBytes("UTF-8")), signers)
    }

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
    expect(!fourObserved.allowsProbationAdmission) &&
    expect(!fourObserved.allowsOpenAdmission) &&
    expect.same(Some(0), fiveObserved.headroom.map(_.margin)) &&
    expect(fiveObserved.allowsProbationAdmission) &&
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

    expect(decision.headroom.exists(_.allowsExpansion)) &&
    expect(decision.allowsProbationAdmission) &&
    expect(!decision.allowsOpenAdmission)
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

    expect.same(None, allowed.headroom) &&
    expect(allowed.allowsProbationAdmission) &&
    expect(allowed.allowsOpenAdmission) &&
    expect(suppressed.allowsProbationAdmission) &&
    expect(!suppressed.allowsOpenAdmission)
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
    expect(inBootstrap.allowsProbationAdmission) &&
    expect(inBootstrap.allowsOpenAdmission) &&
    expect.same(Some(2), postBootstrap.headroom.map(_.nextFinalityFloor)) &&
    expect(!postBootstrap.allowsProbationAdmission) &&
    expect(!postBootstrap.allowsOpenAdmission)
  }

  test("the batch crossing bootstrap threshold must support the floor it activates") {
    val first = peer(1)
    val second = peer(2)
    val committeeOfTwo = Set(first, second)
    val singletonGate = OpenAdmissionPolicy.headroomRequired(
      bootstrapActive = true,
      currentCommitteeSize = 1,
      maxAdmissionSeats = 1,
      bootstrapCompleteProofsThreshold = 3
    )
    val crossingGate = OpenAdmissionPolicy.headroomRequired(
      bootstrapActive = true,
      currentCommitteeSize = 2,
      maxAdmissionSeats = 1,
      bootstrapCompleteProofsThreshold = 3
    )
    val oneSignerCrossing = OpenAdmissionPolicy.evaluate(
      cadenceAllowed = true,
      currentCommittee = committeeOfTwo,
      locallyObservedParentSigners = Some(Set(first)),
      quorumThresholdFraction = 2.0 / 3.0,
      headroomGateActive = crossingGate
    )
    val twoSignerCrossing = OpenAdmissionPolicy.evaluate(
      cadenceAllowed = true,
      currentCommittee = committeeOfTwo,
      locallyObservedParentSigners = Some(committeeOfTwo),
      quorumThresholdFraction = 2.0 / 3.0,
      headroomGateActive = crossingGate
    )

    expect(!singletonGate) &&
    expect(crossingGate) &&
    expect(!oneSignerCrossing.allowsProbationAdmission) &&
    expect(!oneSignerCrossing.allowsOpenAdmission) &&
    expect(twoSignerCrossing.allowsProbationAdmission) &&
    expect(twoSignerCrossing.allowsOpenAdmission)
  }

  test("headroom covers the largest admission batch accepted by a proposal") {
    val fiveObserved = OpenAdmissionPolicy.evaluate(
      cadenceAllowed = true,
      currentCommittee = committee,
      locallyObservedParentSigners = Some(committee.take(5)),
      quorumThresholdFraction = 2.0 / 3.0,
      headroomGateActive = true,
      maxAdmissionSeats = 2
    )
    val sixObserved = OpenAdmissionPolicy.evaluate(
      cadenceAllowed = true,
      currentCommittee = committee,
      locallyObservedParentSigners = Some(committee),
      quorumThresholdFraction = 2.0 / 3.0,
      headroomGateActive = true,
      maxAdmissionSeats = 2
    )

    expect.same(Some(8), fiveObserved.headroom.map(_.nextCommitteeSize)) &&
    expect.same(Some(6), fiveObserved.headroom.map(_.nextFinalityFloor)) &&
    expect(!fiveObserved.allowsProbationAdmission) &&
    expect(!fiveObserved.allowsOpenAdmission) &&
    expect(sixObserved.allowsProbationAdmission) &&
    expect(sixObserved.allowsOpenAdmission)
  }

  test("two-signer recovery grows only after each floor step proves itself") {
    val first = peer(1)
    val second = peer(2)
    val third = peer(3)
    val committeeOfTwo = Set(first, second)
    val committeeOfThree = committeeOfTwo + third

    val twoToThree = OpenAdmissionPolicy.evaluate(
      cadenceAllowed = true,
      currentCommittee = committeeOfTwo,
      locallyObservedParentSigners = Some(committeeOfTwo),
      quorumThresholdFraction = 2.0 / 3.0,
      headroomGateActive = true
    )
    val threeToFourBeforeNewSignerProvesItself = OpenAdmissionPolicy.evaluate(
      cadenceAllowed = true,
      currentCommittee = committeeOfThree,
      locallyObservedParentSigners = Some(committeeOfTwo),
      quorumThresholdFraction = 2.0 / 3.0,
      headroomGateActive = true
    )
    val threeToFourAfterNewSignerProvesItself = OpenAdmissionPolicy.evaluate(
      cadenceAllowed = true,
      currentCommittee = committeeOfThree,
      locallyObservedParentSigners = Some(committeeOfThree),
      quorumThresholdFraction = 2.0 / 3.0,
      headroomGateActive = true
    )

    expect.same(Some(2), twoToThree.headroom.map(_.nextFinalityFloor)) &&
    expect(twoToThree.allowsOpenAdmission) &&
    expect.same(Some(3), threeToFourBeforeNewSignerProvesItself.headroom.map(_.nextFinalityFloor)) &&
    expect(!threeToFourBeforeNewSignerProvesItself.allowsOpenAdmission) &&
    expect(threeToFourAfterNewSignerProvesItself.allowsOpenAdmission)
  }

  test("a floor-raising admission requires three consecutive exact-headroom parents in both lanes") {
    val committeeOfThree = (1 to 3).map(peer).toSet
    val incomplete = proofHistory(List.fill(2)(committeeOfThree))
    val complete = proofHistory(List.fill(3)(committeeOfThree))
    def evaluate(history: AdmissionProofHistory.History, cadenceAllowed: Boolean) =
      OpenAdmissionPolicy.evaluate(
        cadenceAllowed = cadenceAllowed,
        currentCommittee = committeeOfThree,
        locallyObservedParentSigners = Some(committeeOfThree),
        quorumThresholdFraction = 2.0 / 3.0,
        headroomGateActive = true,
        locallyObservedParentProofHistory = Some(history)
      )

    val building = evaluate(incomplete, cadenceAllowed = true)
    val onCadence = evaluate(complete, cadenceAllowed = true)
    val offCadence = evaluate(complete, cadenceAllowed = false)

    expect(building.headroom.exists(_.allowsExpansion)) &&
    expect(building.sustainedHeadroom.exists(e => e.raisesFinalityFloor && !e.allowsAdmission)) &&
    expect(!building.allowsProbationAdmission) &&
    expect(!building.allowsOpenAdmission) &&
    expect(onCadence.allowsProbationAdmission) &&
    expect(onCadence.allowsOpenAdmission) &&
    expect(offCadence.allowsProbationAdmission) &&
    expect(!offCadence.allowsOpenAdmission)
  }

  test("floor-neutral admission does not wait for sustained history") {
    val committeeOfFive = (1 to 5).map(peer).toSet
    val decision = OpenAdmissionPolicy.evaluate(
      cadenceAllowed = true,
      currentCommittee = committeeOfFive,
      locallyObservedParentSigners = Some(committeeOfFive.take(4)),
      quorumThresholdFraction = 2.0 / 3.0,
      headroomGateActive = true,
      locallyObservedParentProofHistory = Some(AdmissionProofHistory.History.empty)
    )

    expect(decision.headroom.exists(_.allowsExpansion)) &&
    expect(decision.sustainedHeadroom.exists(e => !e.raisesFinalityFloor && e.allowsAdmission)) &&
    expect(decision.allowsProbationAdmission) &&
    expect(decision.allowsOpenAdmission)
  }

  test("bootstrap bypass also bypasses incomplete sustained history until the threshold-crossing batch") {
    val singleton = Set(peer(1))
    val decision = OpenAdmissionPolicy.evaluate(
      cadenceAllowed = true,
      currentCommittee = singleton,
      locallyObservedParentSigners = Some(singleton),
      quorumThresholdFraction = 2.0 / 3.0,
      headroomGateActive = false,
      locallyObservedParentProofHistory = Some(AdmissionProofHistory.History.empty)
    )

    expect.same(None, decision.headroom) &&
    expect.same(None, decision.sustainedHeadroom) &&
    expect(decision.allowsProbationAdmission) &&
    expect(decision.allowsOpenAdmission)
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

  test("probation alone opens a bounded grace before the first vote on an off-cadence round") {
    val decision = OpenAdmissionPolicy.preProposalGrace(
      elapsed = Duration.Zero,
      baseGrace = 1500.millis,
      maxAdmissionSeats = 1,
      probationPresent = true,
      hasOpenEvidence = false,
      hasAdmissionVoteEvidence = false,
      hasApplicableCertificate = false,
      requiredProbationObservations = 2,
      probationProbeInterval = 1.second,
      probationProbeTimeout = 1.second
    )

    expect.same(4500.millis, decision.effectiveGrace) &&
    expect(decision.hasAdmissionEvidence) &&
    expect(decision.shouldWait)
  }

  test("probation grace closes on a certificate and is bounded when the target stays unavailable") {
    def decision(elapsed: FiniteDuration, hasCertificate: Boolean) =
      OpenAdmissionPolicy.preProposalGrace(
        elapsed = elapsed,
        baseGrace = 1500.millis,
        maxAdmissionSeats = 1,
        probationPresent = true,
        hasOpenEvidence = false,
        hasAdmissionVoteEvidence = false,
        hasApplicableCertificate = hasCertificate,
        requiredProbationObservations = 2,
        probationProbeInterval = 1.second,
        probationProbeTimeout = 1.second
      )

    expect(!decision(Duration.Zero, hasCertificate = true).shouldWait) &&
    expect(decision(4499.millis, hasCertificate = false).shouldWait) &&
    expect(!decision(4500.millis, hasCertificate = false).shouldWait)
  }

  test("open-only rounds retain the existing base grace") {
    val decision = OpenAdmissionPolicy.preProposalGrace(
      elapsed = 1499.millis,
      baseGrace = 1500.millis,
      maxAdmissionSeats = 1,
      probationPresent = false,
      hasOpenEvidence = true,
      hasAdmissionVoteEvidence = false,
      hasApplicableCertificate = false,
      requiredProbationObservations = 3,
      probationProbeInterval = 1.second,
      probationProbeTimeout = 1.second
    )

    expect.same(1500.millis, decision.effectiveGrace) && expect(decision.shouldWait)
  }
}
