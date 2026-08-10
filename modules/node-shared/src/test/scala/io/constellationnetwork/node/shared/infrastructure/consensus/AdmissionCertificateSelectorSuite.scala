package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.data.NonEmptySet

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration._
import io.constellationnetwork.node.shared.infrastructure.consensus.state.AdmissionCertificateSelector
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import weaver.FunSuite

/** Pure-function coverage for the shared admission-certificate cap. Proposal construction uses entropy ranking; the unreachable over-cap
  * apply defense intentionally retains legacy certificate ordering.
  *
  * Invariants under test:
  *   - the cap is `math.max(0, activeAdmissionMaxExpansionPerRound)` -- EXACTLY the limit `validateProposalAcs` enforces via
  *     `acs_too_many`, so a capped proposal can never be rejected for size
  *   - selection is deterministic under input-ordering permutations (two leaders building from the same assembled set must agree)
  *   - the apply defense keeps lexicographic ordering while proposal construction uses entropy and prioritizes probation recovery
  */
object AdmissionCertificateSelectorSuite extends FunSuite {

  private val facHash: Hash = Hash.fromBytes("facilitators_hash".getBytes("UTF-8"))
  private val lastSnap: Hash = Hash.fromBytes("last_snapshot_hash".getBytes("UTF-8"))

  private def signerProof(tag: String): SignatureProof =
    SignatureProof(Id(Hex(tag)), Signature(Hex("00")))

  private def pid(tag: String): PeerId = PeerId(Hex(tag))

  private def cert(targetTag: String): AdmissionCertificate =
    AdmissionCertificate(
      targetPeer = pid(targetTag),
      reason = AdmissionReason.ReadyAtTip,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      votes = NonEmptySet.of(
        Signed(
          AdmissionVote(pid(targetTag), AdmissionReason.ReadyAtTip, facHash, lastSnap),
          NonEmptySet.of(signerProof("ee" * 32))
        )
      )
    )

  private def signedVote(targetTag: String, signerTag: String, signatureTag: String): Signed[AdmissionVote] =
    Signed(
      AdmissionVote(pid(targetTag), AdmissionReason.ReadyAtTip, facHash, lastSnap),
      NonEmptySet.of(SignatureProof(Id(Hex(signerTag)), Signature(Hex(signatureTag))))
    )

  private val certA = cert("aa" * 32)
  private val certB = cert("bb" * 32)
  private val certC = cert("cc" * 32)
  private val certD = cert("dd" * 32)
  private val all = List(certA, certB, certC, certD)

  test("cap 0 keeps nothing and drops everything (sorted)") {
    val selection = AdmissionCertificateSelector.select(all, 0)
    expect.same(List.empty[AdmissionCertificate], selection.kept).and(expect.same(List(certA, certB, certC, certD), selection.dropped))
  }

  test("negative cap is clamped to 0, matching validation's math.max(0, _)") {
    val selection = AdmissionCertificateSelector.select(all, -3)
    expect.same(List.empty[AdmissionCertificate], selection.kept).and(expect.same(List(certA, certB, certC, certD), selection.dropped))
  }

  test("cap 1 keeps exactly the lexicographically-first target PeerId") {
    val selection = AdmissionCertificateSelector.select(List(certD, certB, certC, certA), 1)
    expect.same(List(certA), selection.kept).and(expect.same(List(certB, certC, certD), selection.dropped))
  }

  test("cap 2 keeps the two lexicographically-first targets in PeerId order") {
    val selection = AdmissionCertificateSelector.select(List(certC, certA, certD, certB), 2)
    expect.same(List(certA, certB), selection.kept).and(expect.same(List(certC, certD), selection.dropped))
  }

  test("cap at or above input size keeps everything (sorted) and drops nothing") {
    val atSize = AdmissionCertificateSelector.select(List(certB, certA), 2)
    val aboveSize = AdmissionCertificateSelector.select(List(certB, certA), 5)
    expect
      .same(List(certA, certB), atSize.kept)
      .and(expect.same(List.empty[AdmissionCertificate], atSize.dropped))
      .and(expect.same(List(certA, certB), aboveSize.kept))
      .and(expect.same(List.empty[AdmissionCertificate], aboveSize.dropped))
  }

  test("selection is deterministic under every input-ordering permutation") {
    val selections = all.permutations.map(AdmissionCertificateSelector.select(_, 1)).toList
    val expected = AdmissionCertificateSelector.Selection(List(certA), List(certB, certC, certD))
    expect(selections.size == 24).and(forEach(selections) { s =>
      expect.same(expected.kept, s.kept).and(expect.same(expected.dropped, s.dropped))
    })
  }

  test("empty input yields empty selection at any cap") {
    val selection = AdmissionCertificateSelector.select(List.empty[AdmissionCertificate], 1)
    expect.same(List.empty[AdmissionCertificate], selection.kept).and(expect.same(List.empty[AdmissionCertificate], selection.dropped))
  }

  test("quorum accounting counts unique voter PeerIds rather than vote wrappers") {
    val target = "aa" * 32
    val duplicateSigner = "11" * 32
    val certificate = AdmissionCertificate(
      targetPeer = pid(target),
      reason = AdmissionReason.ReadyAtTip,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      votes = NonEmptySet.of(
        signedVote(target, duplicateSigner, "01"),
        signedVote(target, duplicateSigner, "02"),
        signedVote(target, "22" * 32, "03")
      )
    )

    expect.same(3, certificate.votes.toNonEmptyList.length) && expect.same(2, AdmissionCertificate.uniqueVoterCount(certificate))
  }

  test("proposal selection is deterministic under permutations and rotates with entropy") {
    val entropyCandidates = (1 to 64).map(i => Hash.fromBytes(s"proposal-entropy-$i".getBytes("UTF-8")))
    val rotatingEntropy = entropyCandidates.find { entropy =>
      AdmissionCertificateSelector.selectForProposal(all, 1, entropy).kept != List(certA)
    }.get
    val selections = all.permutations.map(AdmissionCertificateSelector.selectForProposal(_, 1, rotatingEntropy)).toList

    expect(selections.nonEmpty) &&
    forEach(selections)(selection => expect.same(selections.head, selection)) &&
    expect(selections.head.kept != List(certA))
  }

  test("proposal selection prioritizes probation recovery over open expansion") {
    val entropy = Hash.fromBytes("probation-priority".getBytes("UTF-8"))
    val probation = Set(certD.targetPeer)
    val selections = all.permutations
      .map(AdmissionCertificateSelector.selectForProposal(_, 1, entropy, probation))
      .toList

    expect(selections.nonEmpty) &&
    forEach(selections)(selection => expect.same(List(certD), selection.kept))
  }

  test("apply defense keeps its version-stable legacy ordering") {
    val entropyCandidates = (1 to 64).map(i => Hash.fromBytes(s"proposal-entropy-$i".getBytes("UTF-8")))
    val rotatingEntropy = entropyCandidates.find { entropy =>
      AdmissionCertificateSelector.selectForProposal(all, 1, entropy).kept != List(certA)
    }.get

    expect.same(List(certA), AdmissionCertificateSelector.select(all, 1).kept) &&
    expect(AdmissionCertificateSelector.selectForProposal(all, 1, rotatingEntropy).kept != List(certA))
  }
}
