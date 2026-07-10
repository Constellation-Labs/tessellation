package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.data.NonEmptySet

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration._
import io.constellationnetwork.node.shared.infrastructure.consensus.state.{ProposalRejection, ProposalVccValidator}
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import weaver.FunSuite

/** Pure-function coverage for the shared `ProposalVccValidator.validate` helper. Both the dag-l0 and currency-l0 advancers delegate to this
  * helper, so tests here cover both code paths.
  *
  * `state.initialViewNumber` distinguishes a certified/non-default round seed from a round that ADVANCED to view > 0 via a real VCC. The
  * validator must accept a no-VCC proposal at the seed view (positive case) but still reject a no-VCC proposal once the round has advanced
  * past the seed (negative case). Local retry counters and wall-clock pacemaker hints are intentionally not valid seed evidence. Issue 2
  * from the codex follow-up additionally requires that any embedded VCC's `(fromView, toView)` matches the proposal's view -- a stale 0->1
  * cert preserved across retries must not slip onto a view=2 proposal.
  */
object ProposalVccValidatorSuite extends FunSuite {

  // Deterministic fixture hashes. Distinct values per test concern so log inspection lines up with intent.
  private val proposalHash: Hash = Hash.fromBytes("proposal_hash".getBytes("UTF-8"))
  private val divergentHash: Hash = Hash.fromBytes("divergent_proposal_hash".getBytes("UTF-8"))
  private val facHash: Hash = Hash.fromBytes("facilitators_hash".getBytes("UTF-8"))
  private val otherFacHash: Hash = Hash.fromBytes("other_facilitators_hash".getBytes("UTF-8"))
  private val lastSnap: Hash = Hash.fromBytes("last_snapshot_hash".getBytes("UTF-8"))

  // Three signers, used both as PeerId witness-pool entries and as the SignatureProof.id of each vote.
  // Matches the convention in ViewChangeAssemblySuite where signerPid is derived from the proof tag's hex.
  private def signerProof(tag: String): SignatureProof =
    SignatureProof(Id(Hex(tag)), Signature(Hex("00")))

  private def signerPid(tag: String): PeerId = PeerId(Hex(tag))

  private val signerA = "aa" * 32
  private val signerB = "bb" * 32
  private val signerC = "cc" * 32
  private val outOfPoolSigner = "dd" * 32
  private val poolABC: Set[PeerId] = Set(signerPid(signerA), signerPid(signerB), signerPid(signerC))

  // Default config knobs -- match the production defaults so the suite reads as integration-relevant.
  private val quorum = 1.0
  private val minObs = 5

  private def vote(
    fromView: Long,
    toView: Long,
    fac: Hash = facHash,
    lastSnapshot: Hash = lastSnap,
    highestQc: Option[ProposalQC] = None,
    sigTag: String
  ): Signed[ViewChangeVote] =
    Signed(
      ViewChangeVote(fromView, toView, fac, lastSnapshot, highestQc),
      NonEmptySet.of(signerProof(sigTag))
    )

  private def vcc(
    fromView: Long,
    toView: Long,
    fac: Hash = facHash,
    votes: NonEmptySet[Signed[ViewChangeVote]]
  ): ViewChangeCertificate =
    ViewChangeCertificate(fromView, toView, fac, votes)

  private def timeoutVote(
    fromView: Long,
    toView: Long,
    fac: Hash = facHash,
    lastSnapshot: Hash = lastSnap,
    highestQc: Option[ProposalQC] = None,
    sigTag: String
  ): Signed[TimeoutVote] =
    Signed(
      TimeoutVote(fromView, toView, fac, lastSnapshot, highestQc, TimeoutReason.NoProgress),
      NonEmptySet.of(signerProof(sigTag))
    )

  private def timeoutCertificate(
    fromView: Long,
    toView: Long,
    fac: Hash = facHash,
    lastSnapshot: Hash = lastSnap,
    votes: NonEmptySet[Signed[TimeoutVote]]
  ): TimeoutCertificate =
    TimeoutCertificate(fromView, toView, fac, lastSnapshot, TimeoutReason.NoProgress, votes)

  // ----------------------------------------------------------------------------
  // alpha.90 positive case: initialViewNumber > 0 (round STARTS at the seed view),
  // no VCC stored, proposal at view == initialViewNumber MUST be accepted.
  // This is the self-wedge bug we are fixing -- without `initialViewNumber` the
  // validator rejected every retry with `view{N}_proposal_missing_vcc`.
  // ----------------------------------------------------------------------------
  test("seed view > 0, no VCC, view == initialViewNumber: accepted (round-start bypass)") {
    val result = ProposalVccValidator.validate(
      proposalView = 2L,
      proposalHash = proposalHash,
      proposalVcc = None,
      initialViewNumber = 2,
      coreSize = 3,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      eligibleFacilitators = poolABC,
      roundStartFacilitators = poolABC,
      peerQuality = Map.empty,
      quorumThresholdFraction = quorum,
      minParticipationObservations = minObs
    )
    expect(result.isRight, s"seed-view no-VCC proposal must be accepted, got $result")
  }

  // ----------------------------------------------------------------------------
  // alpha.90 negative case: round has ADVANCED past the seed view (viewNumber > initialViewNumber).
  // No VCC means the leader is shipping a view > initialViewNumber proposal without a certified
  // transition, which must be rejected with the existing `view{N}_proposal_missing_vcc` code.
  // Operator dashboards grep on this prefix.
  // ----------------------------------------------------------------------------
  test("post-seed view > initialViewNumber, no view certificate: rejected with view{N}_proposal_missing_view_cert") {
    val result = ProposalVccValidator.validate(
      proposalView = 3L,
      proposalHash = proposalHash,
      proposalVcc = None,
      initialViewNumber = 1, // round started at view 1, advanced to view 3 -- needs VCC
      coreSize = 3,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      eligibleFacilitators = poolABC,
      roundStartFacilitators = poolABC,
      peerQuality = Map.empty,
      quorumThresholdFraction = quorum,
      minParticipationObservations = minObs
    )
    expect(
      result == Left(ProposalRejection("view3_proposal_missing_view_cert", ProposalRejection.Kind.MissingViewCert)),
      s"post-seed no-cert must be rejected, got $result"
    )
  }

  // ----------------------------------------------------------------------------
  // Sanity coverage for `initialViewNumber == 0` (the v19/pre-alpha.90 default):
  // a view > 0 proposal without a VCC must still be rejected. This guards against
  // an unintended widening of the bypass that would let any view > 0 proposal
  // through with no VCC.
  // ----------------------------------------------------------------------------
  test("initialViewNumber == 0, view > 0, no VCC: rejected (pre-alpha.90 default still gated)") {
    val result = ProposalVccValidator.validate(
      proposalView = 1L,
      proposalHash = proposalHash,
      proposalVcc = None,
      initialViewNumber = 0,
      coreSize = 3,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      eligibleFacilitators = poolABC,
      roundStartFacilitators = poolABC,
      peerQuality = Map.empty,
      quorumThresholdFraction = quorum,
      minParticipationObservations = minObs
    )
    expect(
      result == Left(ProposalRejection("view1_proposal_missing_view_cert", ProposalRejection.Kind.MissingViewCert)),
      s"view-1 no-cert at initialView=0 must be rejected, got $result"
    )
  }

  // ----------------------------------------------------------------------------
  // alpha.90 issue 2: stale-VCC view-mismatch gate. A VCC whose toView != proposal.view
  // MUST be rejected before any of the other Some(vcc) checks fire. Without this gate
  // a 0->1 cert preserved by `clearResourcesPreservingDeclarations` could be embedded
  // on a fresh view=2 seed proposal and pass all later checks.
  // ----------------------------------------------------------------------------
  test("stale 0->1 VCC on a view=2 proposal: rejected with vcc_view_mismatch") {
    val staleVcc = vcc(
      fromView = 0L,
      toView = 1L,
      votes = NonEmptySet.of(
        vote(0L, 1L, sigTag = signerA),
        vote(0L, 1L, sigTag = signerB),
        vote(0L, 1L, sigTag = signerC)
      )
    )
    val result = ProposalVccValidator.validate(
      proposalView = 2L,
      proposalHash = proposalHash,
      proposalVcc = Some(staleVcc),
      initialViewNumber = 0,
      coreSize = 3,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      eligibleFacilitators = poolABC,
      roundStartFacilitators = poolABC,
      peerQuality = Map.empty,
      quorumThresholdFraction = quorum,
      minParticipationObservations = minObs
    )
    val expected =
      ProposalRejection("vcc_view_mismatch vccFromView=0 vccToView=1 proposalView=2", ProposalRejection.Kind.VccViewMismatch)
    expect(result == Left(expected), s"stale 0->1 VCC on view-2 proposal must be rejected, got $result")
  }

  // Tight positive: VCC's (fromView, toView) matches the proposal view -- accepted.
  test("matching VCC: fromView=1 toView=2 on a view=2 proposal -- accepted") {
    val matchingVcc = vcc(
      fromView = 1L,
      toView = 2L,
      votes = NonEmptySet.of(
        vote(1L, 2L, sigTag = signerA),
        vote(1L, 2L, sigTag = signerB),
        vote(1L, 2L, sigTag = signerC)
      )
    )
    val result = ProposalVccValidator.validate(
      proposalView = 2L,
      proposalHash = proposalHash,
      proposalVcc = Some(matchingVcc),
      initialViewNumber = 0,
      coreSize = 3,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      eligibleFacilitators = poolABC,
      roundStartFacilitators = poolABC,
      peerQuality = Map.empty,
      quorumThresholdFraction = quorum,
      minParticipationObservations = minObs
    )
    expect(result.isRight, s"matching VCC on view-2 proposal must be accepted, got $result")
  }

  test("matching TimeoutCertificate: fromView=1 toView=2 on a view=2 proposal -- accepted") {
    val matchingTc = timeoutCertificate(
      fromView = 1L,
      toView = 2L,
      votes = NonEmptySet.of(
        timeoutVote(1L, 2L, sigTag = signerA),
        timeoutVote(1L, 2L, sigTag = signerB),
        timeoutVote(1L, 2L, sigTag = signerC)
      )
    )
    val result = ProposalVccValidator.validate(
      proposalView = 2L,
      proposalHash = proposalHash,
      proposalVcc = None,
      proposalTimeoutCertificate = Some(matchingTc),
      initialViewNumber = 0,
      coreSize = 3,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      eligibleFacilitators = poolABC,
      roundStartFacilitators = poolABC,
      peerQuality = Map.empty,
      quorumThresholdFraction = quorum,
      minParticipationObservations = minObs
    )
    expect(result.isRight, s"matching TC on view-2 proposal must be accepted, got $result")
  }

  // ----------------------------------------------------------------------------
  // view 0 must NEVER carry a VCC: protects against an adversarial leader embedding
  // an arbitrary VCC on a fresh round.
  // ----------------------------------------------------------------------------
  test("view 0 with VCC: rejected with view0_proposal_must_not_carry_view_cert") {
    val anyVcc = vcc(
      fromView = 0L,
      toView = 1L,
      votes = NonEmptySet.of(vote(0L, 1L, sigTag = signerA))
    )
    val result = ProposalVccValidator.validate(
      proposalView = 0L,
      proposalHash = proposalHash,
      proposalVcc = Some(anyVcc),
      initialViewNumber = 0,
      coreSize = 3,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      eligibleFacilitators = poolABC,
      roundStartFacilitators = poolABC,
      peerQuality = Map.empty,
      quorumThresholdFraction = quorum,
      minParticipationObservations = minObs
    )
    expect(
      result == Left(ProposalRejection("view0_proposal_must_not_carry_view_cert")),
      s"view-0 with VCC must be rejected, got $result"
    )
  }

  test("view 0 without VCC: accepted") {
    val result = ProposalVccValidator.validate(
      proposalView = 0L,
      proposalHash = proposalHash,
      proposalVcc = None,
      initialViewNumber = 0,
      coreSize = 3,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      eligibleFacilitators = poolABC,
      roundStartFacilitators = poolABC,
      peerQuality = Map.empty,
      quorumThresholdFraction = quorum,
      minParticipationObservations = minObs
    )
    expect(result.isRight, s"view-0 no-VCC must be accepted, got $result")
  }

  // ----------------------------------------------------------------------------
  // Existing checks that the shared helper must preserve byte-identically:
  // under-quorum, facilitatorsHash mismatch, out-of-pool voter, highest-QC
  // carry-forward violation. The dashboards grep on these prefixes; renaming
  // any rejection code requires updating the dashboards. These tests pin the
  // exact codes so a future refactor that flips a check order trips the suite.
  // ----------------------------------------------------------------------------
  test("under-quorum VCC: rejected with vcc_under_quorum") {
    val underQuorum = vcc(
      fromView = 1L,
      toView = 2L,
      votes = NonEmptySet.of(vote(1L, 2L, sigTag = signerA))
    )
    val result = ProposalVccValidator.validate(
      proposalView = 2L,
      proposalHash = proposalHash,
      proposalVcc = Some(underQuorum),
      initialViewNumber = 0,
      coreSize = 3,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      eligibleFacilitators = poolABC,
      roundStartFacilitators = poolABC,
      peerQuality = Map.empty,
      quorumThresholdFraction = 1.0, // q = 3
      minParticipationObservations = minObs
    )
    expect(
      result == Left(ProposalRejection("vcc_under_quorum votes=1 required=3")),
      s"under-quorum VCC must be rejected, got $result"
    )
  }

  test("facilitatorsHash mismatch: rejected with vcc_facilitators_mismatch") {
    val mismatchedVcc = vcc(
      fromView = 1L,
      toView = 2L,
      fac = otherFacHash,
      votes = NonEmptySet.of(
        vote(1L, 2L, fac = otherFacHash, sigTag = signerA),
        vote(1L, 2L, fac = otherFacHash, sigTag = signerB),
        vote(1L, 2L, fac = otherFacHash, sigTag = signerC)
      )
    )
    val result = ProposalVccValidator.validate(
      proposalView = 2L,
      proposalHash = proposalHash,
      proposalVcc = Some(mismatchedVcc),
      initialViewNumber = 0,
      coreSize = 3,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      eligibleFacilitators = poolABC,
      roundStartFacilitators = poolABC,
      peerQuality = Map.empty,
      quorumThresholdFraction = quorum,
      minParticipationObservations = minObs
    )
    expect(result.isLeft, s"facilitatorsHash mismatch must be rejected, got $result")
    expect(
      result.swap.exists(_.code.startsWith("vcc_facilitators_mismatch")),
      s"rejection code must start with vcc_facilitators_mismatch, got $result"
    )
  }

  test("lastSnapshotHash mismatch: rejected with vcc_last_snapshot_mismatch") {
    val wrongLastSnap = Hash.fromBytes("wrong_last_snapshot_hash".getBytes("UTF-8"))
    val mismatchedVcc = vcc(
      fromView = 1L,
      toView = 2L,
      votes = NonEmptySet.of(
        vote(1L, 2L, sigTag = signerA),
        vote(1L, 2L, sigTag = signerB),
        vote(1L, 2L, lastSnapshot = wrongLastSnap, sigTag = signerC)
      )
    )
    val result = ProposalVccValidator.validate(
      proposalView = 2L,
      proposalHash = proposalHash,
      proposalVcc = Some(mismatchedVcc),
      initialViewNumber = 0,
      coreSize = 3,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      eligibleFacilitators = poolABC,
      roundStartFacilitators = poolABC,
      peerQuality = Map.empty,
      quorumThresholdFraction = quorum,
      minParticipationObservations = minObs
    )
    expect(result.isLeft, s"lastSnapshotHash mismatch must be rejected, got $result")
    expect(
      result.swap.exists(_.code.startsWith("vcc_last_snapshot_mismatch")),
      s"rejection code must start with vcc_last_snapshot_mismatch, got $result"
    )
  }

  test("voter not in witness pool: rejected with vcc_voter_not_in_pool") {
    val poolingExcludesDD = poolABC
    val badVcc = vcc(
      fromView = 1L,
      toView = 2L,
      votes = NonEmptySet.of(
        vote(1L, 2L, sigTag = signerA),
        vote(1L, 2L, sigTag = signerB),
        vote(1L, 2L, sigTag = outOfPoolSigner)
      )
    )
    val result = ProposalVccValidator.validate(
      proposalView = 2L,
      proposalHash = proposalHash,
      proposalVcc = Some(badVcc),
      initialViewNumber = 0,
      coreSize = 3,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      eligibleFacilitators = poolingExcludesDD,
      roundStartFacilitators = poolingExcludesDD,
      peerQuality = Map.empty,
      quorumThresholdFraction = quorum,
      minParticipationObservations = minObs
    )
    expect(result.isLeft, s"out-of-pool voter must be rejected, got $result")
    expect(
      result.swap.exists(_.code.startsWith("vcc_voter_not_in_pool")),
      s"rejection code must start with vcc_voter_not_in_pool, got $result"
    )
  }

  test("highest-QC carry-forward violation: rejected with highest_qc_carry_forward_violation") {
    val carrier = ProposalQC(
      view = 0L,
      proposalHash = divergentHash, // the QC says hashX was committed
      facilitatorsHash = facHash,
      signatures = NonEmptySet.of(signerProof(signerA))
    )
    val carryForward = vcc(
      fromView = 1L,
      toView = 2L,
      votes = NonEmptySet.of(
        vote(1L, 2L, highestQc = Some(carrier), sigTag = signerA),
        vote(1L, 2L, highestQc = Some(carrier), sigTag = signerB),
        vote(1L, 2L, highestQc = Some(carrier), sigTag = signerC)
      )
    )
    val result = ProposalVccValidator.validate(
      proposalView = 2L,
      proposalHash = proposalHash, // proposal says hashY, but QC bound hashX
      proposalVcc = Some(carryForward),
      initialViewNumber = 0,
      coreSize = 3,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      eligibleFacilitators = poolABC,
      roundStartFacilitators = poolABC,
      peerQuality = Map.empty,
      quorumThresholdFraction = quorum,
      minParticipationObservations = minObs
    )
    expect(result.isLeft, s"carry-forward violation must be rejected, got $result")
    expect(
      result.swap.exists(_.code.startsWith("highest_qc_carry_forward_violation")),
      s"rejection code must start with highest_qc_carry_forward_violation, got $result"
    )
  }

  test("TC divergent highest-QC at max view: rejected with tc_divergent_highest_qc") {
    val qcA = ProposalQC(
      view = 1L,
      proposalHash = proposalHash,
      facilitatorsHash = facHash,
      signatures = NonEmptySet.of(signerProof(signerA))
    )
    val qcB = ProposalQC(
      view = 1L,
      proposalHash = divergentHash,
      facilitatorsHash = facHash,
      signatures = NonEmptySet.of(signerProof(signerB))
    )
    val divergentTc = timeoutCertificate(
      fromView = 1L,
      toView = 2L,
      votes = NonEmptySet.of(
        timeoutVote(1L, 2L, highestQc = Some(qcA), sigTag = signerA),
        timeoutVote(1L, 2L, highestQc = Some(qcB), sigTag = signerB),
        timeoutVote(1L, 2L, highestQc = Some(qcA), sigTag = signerC)
      )
    )
    val result = ProposalVccValidator.validate(
      proposalView = 2L,
      proposalHash = proposalHash,
      proposalVcc = None,
      proposalTimeoutCertificate = Some(divergentTc),
      initialViewNumber = 0,
      coreSize = 3,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      eligibleFacilitators = poolABC,
      roundStartFacilitators = poolABC,
      peerQuality = Map.empty,
      quorumThresholdFraction = quorum,
      minParticipationObservations = minObs
    )
    expect(result.isLeft, s"divergent TC highest-QC must be rejected, got $result")
    expect(
      result.swap.exists(_.code.startsWith("tc_divergent_highest_qc")),
      s"rejection code must start with tc_divergent_highest_qc, got $result"
    )
  }

  // ----------------------------------------------------------------------------
  // Solo-core (Core <= 1) bypass: no quorum to assemble a VCC, so a no-VCC view > 0
  // proposal is the only achievable outcome and must be accepted. Already covered by
  // the alpha.89 path -- pinning here so the shared helper preserves it after refactor.
  // ----------------------------------------------------------------------------
  test("solo-core (coreSize=1), view > 0, no VCC: accepted (alpha.89 bypass)") {
    val result = ProposalVccValidator.validate(
      proposalView = 1L,
      proposalHash = proposalHash,
      proposalVcc = None,
      initialViewNumber = 0,
      coreSize = 1,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      eligibleFacilitators = poolABC,
      roundStartFacilitators = poolABC,
      peerQuality = Map.empty,
      quorumThresholdFraction = quorum,
      minParticipationObservations = minObs
    )
    expect(result.isRight, s"solo-core no-VCC must be accepted, got $result")
  }
}
