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

/** Pure-function coverage for the shared `AdmissionCertificateSelector.select` helper. Both the dag-l0 and currency-l0 advancers delegate
  * every assembled-admission-certificate attach site (initial proposal build, leader re-spread) plus the apply-site defense-in-depth to
  * this helper, so tests here cover both code paths.
  *
  * Invariants under test:
  *   - the cap is `math.max(0, activeAdmissionMaxExpansionPerRound)` -- EXACTLY the limit `validateProposalAcs` enforces via
  *     `acs_too_many`, so a capped proposal can never be rejected for size
  *   - selection is deterministic under input-ordering permutations (two leaders building from the same assembled set must agree)
  *   - kept certs are the lexicographically-first targets by `PeerId` value, per `AdmissionCertificate.ordering`
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
}
