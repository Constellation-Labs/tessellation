package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.node.shared.infrastructure.consensus.CertifiedMembershipTransition.Kind
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.FunSuite

object CertifiedMembershipTransitionSuite extends FunSuite {

  private def peer(index: Int): PeerId = PeerId(Hex(f"$index%0128x"))

  private val a = peer(1)
  private val b = peer(2)
  private val c = peer(3)
  private val d = peer(4)
  private val committee = List(a, b, c)
  private val quorumFraction = 2.0 / 3.0

  test("hold and expansion preserve existing admission semantics") {
    val hold = CertifiedMembershipTransition.validate(committee.toSet, Set.empty, Set.empty, maxChanges = 1)
    val expansion = CertifiedMembershipTransition.applyTo(committee, Set(d), Set.empty, maxChanges = 1)

    expect(hold.exists(_.kind == Kind.Hold)) &&
    expect.same(Right(List(a, b, c, d)), expansion)
  }

  test("one-for-one replacement preserves cardinality and inherited ordering") {
    val result = CertifiedMembershipTransition.applyTo(committee, Set(d), Set(b), maxChanges = 1)

    expect.same(Right(List(a, c, d)), result) &&
    expect(result.exists(_.size == committee.size))
  }

  test("Core and Tier-1 seats use the same cardinality-preserving transition") {
    val core = Set(a, b)
    val tier1 = Set(c)
    val roundStart = core ++ tier1
    val replaceCore = CertifiedMembershipTransition.validate(roundStart, Set(d), Set(b), maxChanges = 1)
    val replaceTier1 = CertifiedMembershipTransition.validate(roundStart, Set(d), Set(c), maxChanges = 1)

    expect(
      replaceCore.exists(result =>
        result.kind == Kind.Replacement &&
          result.nextCommittee.size == roundStart.size &&
          !result.nextCommittee.contains(b) &&
          result.nextCommittee.contains(d)
      )
    ) &&
    expect(
      replaceTier1.exists(result =>
        result.kind == Kind.Replacement &&
          result.nextCommittee.size == roundStart.size &&
          !result.nextCommittee.contains(c) &&
          result.nextCommittee.contains(d)
      )
    )
  }

  test("replacement result is independent of set construction order") {
    val first = CertifiedMembershipTransition.applyTo(committee, List(peer(5), d).toSet, List(b, c).toSet, maxChanges = 2)
    val second = CertifiedMembershipTransition.applyTo(committee, List(d, peer(5)).toSet, List(c, b).toSet, maxChanges = 2)

    expect.same(first, second) && expect.same(Right(List(a, d, peer(5))), first)
  }

  test("standalone contraction and unequal replacement batches are rejected") {
    val contraction = CertifiedMembershipTransition.validate(committee.toSet, Set.empty, Set(b), maxChanges = 1)
    val unequal = CertifiedMembershipTransition.validate(committee.toSet, Set(d), Set(a, b), maxChanges = 2)

    expect(contraction.left.exists(_ == "certified_membership_eviction_requires_equal_admission")) &&
    expect(unequal.left.exists(_ == "certified_membership_eviction_requires_equal_admission"))
  }

  test("duplicate admission and eviction certificate targets are rejected before set conversion") {
    val duplicateAdmission =
      CertifiedMembershipTransition.validateCertificateTargets(committee.toSet, List(d, d), List.empty, maxChanges = 2)
    val duplicateEviction =
      CertifiedMembershipTransition.validateCertificateTargets(committee.toSet, List(d, peer(5)), List(b, b), maxChanges = 2)

    expect.same(Left("certified_membership_duplicate_admission_target"), duplicateAdmission) &&
    expect.same(Left("certified_membership_duplicate_eviction_target"), duplicateEviction)
  }

  test("atomic replacement rejects a probation-lane admission but admission-only recovery remains valid") {
    val probation = peer(5)
    val paired = CertifiedMembershipTransition.validateReplacementAdmissionLane(
      admittedPeers = Set(probation),
      evictedPeers = Set(c),
      probationPeers = Set(probation)
    )
    val admissionOnly = CertifiedMembershipTransition.validateReplacementAdmissionLane(
      admittedPeers = Set(probation),
      evictedPeers = Set.empty,
      probationPeers = Set(probation)
    )
    val openReplacement = CertifiedMembershipTransition.validateReplacementAdmissionLane(
      admittedPeers = Set(d),
      evictedPeers = Set(c),
      probationPeers = Set(probation)
    )

    expect.same(Left("certified_membership_replacement_requires_open_ready_admission"), paired) &&
    expect.same(Right(()), admissionOnly) &&
    expect.same(Right(()), openReplacement)
  }

  test("v35 round-start committee ignores asymmetric buffered withdrawal arrival") {
    val observedEarly = Set(a)
    val observedLate = Set.empty[PeerId]

    expect.same(
      CertifiedRoundCommitteeProjector.roundStartWithdrawals(certifiedConsensusActive = true, observedLate),
      CertifiedRoundCommitteeProjector.roundStartWithdrawals(certifiedConsensusActive = true, observedEarly)
    ) &&
    expect.same(
      observedEarly,
      CertifiedRoundCommitteeProjector.roundStartWithdrawals(certifiedConsensusActive = false, observedEarly)
    )
  }

  test("local proof subsets can abstain from expansion but cannot alter a same-size replacement") {
    val current = (1 to 6).map(peer).toSet
    val replacement = peer(7)
    val sparseProofs = (1 to 4).map(peer).toSet
    val richProofs = (1 to 5).map(peer).toSet
    val sparseEvaluation = FinalityHeadroom.evaluate(current, sparseProofs, quorumFraction)
    val richEvaluation = FinalityHeadroom.evaluate(current, richProofs, quorumFraction)
    val appliedReplacement =
      CertifiedMembershipTransition.applyTo(current.toList.sorted, Set(replacement), Set(peer(6)), maxChanges = 1)
    val replacementPreservesFloor = appliedReplacement.exists { nextCommittee =>
      FinalityHeadroom.evaluate(nextCommittee.toSet, richProofs, quorumFraction).currentFinalityFloor ==
        sparseEvaluation.currentFinalityFloor
    }

    expect(!CertifiedMembershipTransition.allowsPrepareVote(current, sparseProofs, Set(replacement), Set.empty, quorumFraction, 1)) &&
    expect(CertifiedMembershipTransition.allowsPrepareVote(current, richProofs, Set(replacement), Set.empty, quorumFraction, 1)) &&
    expect(CertifiedMembershipTransition.allowsPrepareVote(current, sparseProofs, Set(replacement), Set(peer(6)), quorumFraction, 1)) &&
    expect(CertifiedMembershipTransition.allowsPrepareVote(current, richProofs, Set(replacement), Set(peer(6)), quorumFraction, 1)) &&
    expect(appliedReplacement.exists(_.size == current.size)) &&
    expect.same(sparseEvaluation.currentFinalityFloor, richEvaluation.currentFinalityFloor) &&
    expect(replacementPreservesFloor)
  }

  test("proposal construction suppresses an unpaired ACS in the dead band but preserves replacement and genuine expansion") {
    val current = (1 to 4).map(peer).toSet
    val candidate = peer(5)
    val silent = peer(4)
    val deadBandSigners = (1 to 3).map(peer).toSet
    val fullSigners = current

    val unpaired = CertifiedMembershipTransition.selectForProposal(
      current,
      deadBandSigners,
      admissions = List(candidate),
      evictions = List.empty[PeerId],
      (peerId: PeerId) => peerId,
      (peerId: PeerId) => peerId,
      quorumFraction,
      maxChanges = 1
    )
    val paired = CertifiedMembershipTransition.selectForProposal(
      current,
      deadBandSigners,
      admissions = List(candidate),
      evictions = List(silent),
      (peerId: PeerId) => peerId,
      (peerId: PeerId) => peerId,
      quorumFraction,
      maxChanges = 1
    )
    val expansion = CertifiedMembershipTransition.selectForProposal(
      current,
      fullSigners,
      admissions = List(candidate),
      evictions = List.empty[PeerId],
      (peerId: PeerId) => peerId,
      (peerId: PeerId) => peerId,
      quorumFraction,
      maxChanges = 1
    )

    expect.same(CertifiedMembershipTransition.ProposalSelection(List.empty, List.empty), unpaired) &&
    expect.same(CertifiedMembershipTransition.ProposalSelection(List(candidate), List(silent)), paired) &&
    expect.same(CertifiedMembershipTransition.ProposalSelection(List(candidate), List.empty), expansion)
  }

  test("from-genesis singleton bootstrap admits exactly one second signer without weakening mature headroom") {
    val singleton = Set(peer(1))
    val candidate = peer(2)
    val observed = singleton

    val strict = CertifiedMembershipTransition.allowsPrepareVote(
      singleton,
      observed,
      Set(candidate),
      Set.empty,
      quorumFraction,
      maxChanges = 1
    )
    val bootstrap = CertifiedMembershipTransition.allowsPrepareVote(
      singleton,
      observed,
      Set(candidate),
      Set.empty,
      quorumFraction,
      maxChanges = 1,
      allowSingletonBootstrapExpansion = true
    )
    val missingParentSigner = CertifiedMembershipTransition.allowsPrepareVote(
      singleton,
      Set.empty,
      Set(candidate),
      Set.empty,
      quorumFraction,
      maxChanges = 1,
      allowSingletonBootstrapExpansion = true
    )
    val proposal = CertifiedMembershipTransition.selectForProposal(
      singleton,
      observed,
      admissions = List(candidate),
      evictions = List.empty[PeerId],
      (peerId: PeerId) => peerId,
      (peerId: PeerId) => peerId,
      quorumFraction,
      maxChanges = 1,
      allowSingletonBootstrapExpansion = true
    )

    expect(!strict) &&
    expect(bootstrap) &&
    expect(!missingParentSigner) &&
    expect.same(CertifiedMembershipTransition.ProposalSelection(List(candidate), List.empty), proposal)
  }

  test("invalid membership and cap violations fail closed") {
    val overlap = CertifiedMembershipTransition.validate(committee.toSet, Set(b), Set(b), maxChanges = 1)
    val unknownEviction = CertifiedMembershipTransition.validate(committee.toSet, Set(d), Set(peer(9)), maxChanges = 1)
    val seatedAdmission = CertifiedMembershipTransition.validate(committee.toSet, Set(a), Set.empty, maxChanges = 1)
    val overCap = CertifiedMembershipTransition.validate(committee.toSet, Set(d, peer(5)), Set.empty, maxChanges = 1)

    expect(overlap.isLeft) &&
    expect(unknownEviction.isLeft) &&
    expect(seatedAdmission.isLeft) &&
    expect(overCap.isLeft)
  }

  test("rc7 N=4/S=3 fixed point heals only through one Core-certified atomic replacement") {
    val current = (1 to 4).map(peer).toList
    val silent = current.last
    val observed = current.take(3).toSet
    val admitted = peer(5)
    val headroom = FinalityHeadroom.evaluate(current.toSet, observed, quorumFraction)
    val expansionVote = CertifiedMembershipTransition.allowsPrepareVote(
      current.toSet,
      observed,
      Set(admitted),
      Set.empty,
      quorumFraction,
      maxChanges = 1
    )
    val replacementVote = CertifiedMembershipTransition.allowsPrepareVote(
      current.toSet,
      observed,
      Set(admitted),
      Set(silent),
      quorumFraction,
      maxChanges = 1
    )
    val replacement = CertifiedMembershipTransition.applyTo(current, Set(admitted), Set(silent), maxChanges = 1)
    val committees = CommitteeBuilder.build(
      candidates = replacement.toOption.get,
      priorTiers = scala.collection.immutable.SortedMap(current.map(_ -> TierTransitions.Core): _*),
      peerQuality = Map.empty,
      coreFloor = 3,
      minObservations = 3,
      minRatio = 2.0 / 3.0,
      forcedTier1Peers = Set(admitted)
    )

    expect.same(3, headroom.currentFinalityFloor) &&
    expect.same(4, headroom.nextFinalityFloor) &&
    expect(!expansionVote) &&
    expect(replacementVote) &&
    expect(replacement.exists(_.size == 4)) &&
    expect(committees.tier1.contains(admitted)) &&
    expect(!committees.core.contains(admitted)) &&
    expect.same(3, FinalityHeadroom.evaluate(replacement.toOption.get.toSet, observed, quorumFraction).currentFinalityFloor) &&
    expect(CertifiedMembershipTransition.validate(current.toSet, Set.empty, Set(silent), 1).isLeft)
  }

  test("N=3/S=2 cannot expand and below-current-quorum membership requires operator recovery") {
    val three = (1 to 3).map(peer).toList
    val twoSigners = three.take(2).toSet
    val expansion = CertifiedMembershipTransition.allowsPrepareVote(
      three.toSet,
      twoSigners,
      Set(peer(4)),
      Set.empty,
      quorumFraction,
      1
    )
    val four = (1 to 4).map(peer).toList
    val belowCurrentQuorum = FinalityHeadroom.evaluate(four.toSet, four.take(2).toSet, quorumFraction)

    expect(!expansion) &&
    expect.same(3, belowCurrentQuorum.currentFinalityFloor) &&
    expect(belowCurrentQuorum.allowsSilentEviction) &&
    expect(CertifiedMembershipTransition.validate(four.toSet, Set.empty, Set(four.last), 1).isLeft)
  }
}
