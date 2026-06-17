package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.data.NonEmptySet
import cats.syntax.all._

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration._
import io.constellationnetwork.node.shared.infrastructure.consensus.message.ConsensusPeerDeclaration
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import io.circe.parser.decode
import io.circe.syntax._
import weaver.FunSuite

object ViewChangeCertificateSuite extends FunSuite {

  private val hashA: Hash = Hash.fromBytes("A".getBytes("UTF-8"))
  private val hashB: Hash = Hash.fromBytes("B".getBytes("UTF-8"))
  private val facHash: Hash = Hash.fromBytes("FAC".getBytes("UTF-8"))
  private val lastSnap: Hash = Hash.fromBytes("LAST".getBytes("UTF-8"))

  private def dummyProof(tag: String): SignatureProof =
    SignatureProof(Id(Hex(tag.getBytes("UTF-8").map(b => f"$b%02x").mkString)), Signature(Hex("00")))

  private def qc(view: Long, proposalHash: Hash): ProposalQC =
    ProposalQC(view, proposalHash, facHash, NonEmptySet.of(dummyProof("qcsig")))

  private def vote(fromView: Long, highestKnownQc: Option[ProposalQC], proofTag: String): Signed[ViewChangeVote] =
    Signed(
      ViewChangeVote(
        fromView = fromView,
        toView = fromView + 1,
        facilitatorsHash = facHash,
        lastSnapshotHash = lastSnap,
        highestKnownQc = highestKnownQc
      ),
      NonEmptySet.of(dummyProof(proofTag))
    )

  private def timeoutVote(
    fromView: Long,
    highestKnownQc: Option[ProposalQC],
    proofTag: String,
    reason: TimeoutReason = TimeoutReason.NoProgress
  ): Signed[TimeoutVote] =
    Signed(
      TimeoutVote(
        fromView = fromView,
        toView = fromView + 1,
        facilitatorsHash = facHash,
        lastSnapshotHash = lastSnap,
        highestKnownQc = highestKnownQc,
        reason = reason
      ),
      NonEmptySet.of(dummyProof(proofTag))
    )

  test("highestQcInVcc returns the single highest-view QC") {
    val vcc = ViewChangeCertificate(
      fromView = 3L,
      toView = 4L,
      facilitatorsHash = facHash,
      votes = NonEmptySet.of(
        vote(3L, qc(view = 1L, proposalHash = hashA).some, "v1"),
        vote(3L, qc(view = 2L, proposalHash = hashB).some, "v2"),
        vote(3L, None, "v3")
      )
    )
    val result = vcc.highestQcInVcc
    expect(result.exists(_.view === 2L) && result.exists(_.proposalHash === hashB), s"expected highest QC at view 2 hashB, got: $result")
  }

  test("highestQcInVcc returns None when two different hashes present at same highest view") {
    val vcc = ViewChangeCertificate(
      fromView = 3L,
      toView = 4L,
      facilitatorsHash = facHash,
      votes = NonEmptySet.of(
        vote(3L, qc(view = 2L, proposalHash = hashA).some, "v1"),
        vote(3L, qc(view = 2L, proposalHash = hashB).some, "v2")
      )
    )
    val result = vcc.highestQcInVcc
    expect(result.isEmpty, s"should return None when same-view differ-hash QCs present, got: $result")
  }

  test("highestQcInVcc returns None when no votes carry a QC") {
    val vcc = ViewChangeCertificate(
      fromView = 3L,
      toView = 4L,
      facilitatorsHash = facHash,
      votes = NonEmptySet.of(vote(3L, None, "v1"), vote(3L, None, "v2"))
    )
    val result = vcc.highestQcInVcc
    expect(result.isEmpty, s"should return None when no votes carry QCs, got: $result")
  }

  // Regression: pre-fix this serialization path NPE'd at `Signed.scala:56` with
  // `circeGenericEncoderForvalue is null`. Root cause: in declaration.scala, `Proposal`
  // was declared before `ViewChangeCertificate`, so at macro-expansion time the derived
  // `Encoder[Option[ViewChangeCertificate]]` captured a forward-reference chain that
  // resolved to null at runtime. Every view>0 proposal (which embeds a VCC) crashed in
  // `spreadProposal`, the leader never delivered, and rounds thrashed indefinitely. The
  // cycle was observed in fork-recovery E2E on Apr 20 2026 — cluster got stuck at ord 23
  // for 906s post-isolation because none of the four active peers could ever spread a
  // view-1 proposal. The underlying VCC-carrying Proposal serialization must stay exercised
  // so a future reordering doesn't silently re-introduce the bug.
  test("JSON round-trip: Proposal with Some(vcc) encodes without throwing and decodes back equal") {
    val vcc = ViewChangeCertificate(
      fromView = 0L,
      toView = 1L,
      facilitatorsHash = facHash,
      votes = NonEmptySet.of(vote(0L, None, "v1"), vote(0L, None, "v2"), vote(0L, qc(view = 0L, hashA).some, "v3"))
    )
    val proposal = Proposal(
      hash = hashA,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      view = 1L,
      vcc = vcc.some
    )
    val json = proposal.asJson
    val roundTripped = decode[Proposal](json.noSpaces)

    expect(roundTripped.exists(_ === proposal), s"round-trip must preserve Proposal, got: $roundTripped").and(
      expect(
        json.hcursor.downField("vcc").as[Option[ViewChangeCertificate]].exists(_.exists(_.votes.length === 3)),
        "serialized JSON must carry the VCC with all 3 signed votes intact"
      )
    )
  }

  test("DIAGNOSTIC: bare ViewChangeVote encodes without throwing") {
    val v = vote(0L, None, "diag").value
    val json = v.asJson
    expect(json.isObject, s"bare VCV should encode to object, got: $json")
  }

  test("DIAGNOSTIC: bare Signed[ViewChangeVote] encodes without throwing") {
    val sv = vote(0L, None, "diag")
    val json = sv.asJson
    expect(json.isObject, s"bare Signed[VCV] should encode to object, got: $json")
  }

  test("DIAGNOSTIC: bare ViewChangeCertificate encodes without throwing") {
    val vcc = ViewChangeCertificate(
      fromView = 0L,
      toView = 1L,
      facilitatorsHash = facHash,
      votes = NonEmptySet.of(vote(0L, None, "a"))
    )
    val json = vcc.asJson
    expect(json.isObject, s"bare VCC should encode to object, got: $json")
  }

  test("JSON round-trip: ConsensusPeerDeclaration wrapping Proposal-with-VCC encodes via the spreadProposal path") {
    // Mirrors the exact wire shape that `spreadProposal` hands to `gossip.spreadDirect`:
    // `ConsensusPeerDeclaration(key, Proposal(..., vcc = Some(vcc)))`. This is the combination
    // that failed in production — the generic `Proposal` encoder's VCC-subencoder was null.
    val vcc = ViewChangeCertificate(
      fromView = 0L,
      toView = 1L,
      facilitatorsHash = facHash,
      votes = NonEmptySet.of(vote(0L, None, "a"), vote(0L, None, "b"))
    )
    val decl = ConsensusPeerDeclaration[Long, Proposal](
      key = 24L,
      declaration = Proposal(hashA, facHash, lastSnap, view = 1L, vcc = vcc.some)
    )
    val json = decl.asJson
    expect(json.isObject, s"encoded wire payload must be a JSON object, got: $json")
  }

  test("JSON round-trip: Proposal with Some(timeoutCertificate) encodes without throwing and decodes back equal") {
    val tc = TimeoutCertificate(
      fromView = 0L,
      toView = 1L,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      reason = TimeoutReason.NoProgress,
      votes = NonEmptySet.of(
        timeoutVote(0L, None, "t1"),
        timeoutVote(0L, qc(view = 0L, hashA).some, "t2"),
        timeoutVote(0L, None, "t3")
      )
    )
    val proposal = Proposal(
      hash = hashA,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      view = 1L,
      vcc = None,
      timeoutCertificate = tc.some
    )
    val json = proposal.asJson
    val roundTripped = decode[Proposal](json.noSpaces)

    expect(roundTripped.exists(_ === proposal), s"round-trip must preserve Proposal, got: $roundTripped").and(
      expect(
        json.hcursor.downField("timeoutCertificate").as[Option[TimeoutCertificate]].exists(_.exists(_.votes.length === 3)),
        "serialized JSON must carry the TC with all 3 signed votes intact"
      )
    )
  }

  test("JSON round-trip: ConsensusPeerDeclaration wrapping Proposal-with-TC encodes via the spreadProposal path") {
    val tc = TimeoutCertificate(
      fromView = 0L,
      toView = 1L,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      reason = TimeoutReason.NoProgress,
      votes = NonEmptySet.of(timeoutVote(0L, None, "ta"), timeoutVote(0L, None, "tb"))
    )
    val decl = ConsensusPeerDeclaration[Long, Proposal](
      key = 25L,
      declaration = Proposal(hashA, facHash, lastSnap, view = 1L, vcc = None, timeoutCertificate = tc.some)
    )
    val json = decl.asJson
    expect(json.isObject, s"encoded wire payload must be a JSON object, got: $json")
  }
}
