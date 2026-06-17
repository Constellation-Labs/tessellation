package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.data.NonEmptySet
import cats.syntax.all._

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration._
import io.constellationnetwork.node.shared.infrastructure.consensus.message.{ConsensusPeerDeclaration, ConsensusPeerEvictionVote}
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import io.circe.parser.decode
import io.circe.syntax._
import weaver.FunSuite

// Regression coverage for the `Signed[EvictionVote]` / `EvictionCertificate` codec chain.
// The VCC shipping in April 2026 hit a null-pointer exception at Signed.scala:56 because
// derevo's magnolia-derived `Encoder[Proposal]` captured a forward-reference to
// `Encoder[Signed[ViewChangeVote]]` that resolved to null when Proposal had been declared
// before VCC in the source file. The eviction types follow the same pattern — declared
// BEFORE Proposal with explicit `Encoder.instance` / `HCursor` codecs — and these tests
// lock that ordering in so a future reordering does not silently reintroduce the bug.
object EvictionCertificateSuite extends FunSuite {

  private val facHash: Hash = Hash.fromBytes("FAC".getBytes("UTF-8"))
  private val lastSnap: Hash = Hash.fromBytes("LAST".getBytes("UTF-8"))
  private val hashA: Hash = Hash.fromBytes("A".getBytes("UTF-8"))
  private val targetA: PeerId = PeerId(Hex("aa" * 64))
  private val targetB: PeerId = PeerId(Hex("bb" * 64))

  private def dummyProof(tag: String): SignatureProof =
    SignatureProof(Id(Hex(tag.getBytes("UTF-8").map(b => f"$b%02x").mkString)), Signature(Hex("00")))

  private def vote(target: PeerId, reason: EvictionReason, proofTag: String): Signed[EvictionVote] =
    Signed(
      EvictionVote(
        targetPeer = target,
        reason = reason,
        facilitatorsHash = facHash,
        lastSnapshotHash = lastSnap
      ),
      NonEmptySet.of(dummyProof(proofTag))
    )

  // === JSON round-trip tests (regression against the VCC-style encoder NPE) ===

  test("JSON round-trip: Signed[EvictionVote] encodes without throwing and decodes back equal") {
    val sv = vote(targetA, EvictionReason.Silent, "v1")
    val json = sv.asJson
    val roundTripped = decode[Signed[EvictionVote]](json.noSpaces)
    expect(roundTripped.exists(_ === sv), s"round-trip must preserve Signed[EvictionVote], got: $roundTripped").and(
      expect(
        json.hcursor.downField("value").downField("targetPeer").as[PeerId].exists(_ === targetA),
        "serialized JSON must carry the targetPeer field intact"
      )
    )
  }

  test("JSON round-trip: EvictionCertificate with multiple votes encodes without throwing and decodes back equal") {
    val cert = EvictionCertificate(
      targetPeer = targetA,
      reason = EvictionReason.Silent,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      votes = NonEmptySet.of(
        vote(targetA, EvictionReason.Silent, "v1"),
        vote(targetA, EvictionReason.Silent, "v2"),
        vote(targetA, EvictionReason.Silent, "v3")
      )
    )
    val json = cert.asJson
    val roundTripped = decode[EvictionCertificate](json.noSpaces)
    expect(roundTripped.exists(_ === cert), s"round-trip must preserve EvictionCertificate, got: $roundTripped").and(
      expect(
        json.hcursor.downField("votes").as[NonEmptySet[Signed[EvictionVote]]].exists(_.length === 3),
        "serialized JSON must carry the vote set with all 3 signed votes intact"
      )
    )
  }

  // === Diagnostic tests for individual layers ===

  test("DIAGNOSTIC: bare EvictionReason.Silent encodes without throwing") {
    val r: EvictionReason = EvictionReason.Silent
    val json = r.asJson
    expect(json.isObject || json.isString, s"EvictionReason should encode to object or string, got: $json")
  }

  test("DIAGNOSTIC: bare EvictionVote encodes without throwing") {
    val v = vote(targetA, EvictionReason.Silent, "diag").value
    val json = v.asJson
    expect(json.isObject, s"bare EvictionVote should encode to object, got: $json")
  }

  test("DIAGNOSTIC: bare Signed[EvictionVote] encodes without throwing") {
    val sv = vote(targetA, EvictionReason.Silent, "diag")
    val json = sv.asJson
    expect(json.isObject, s"bare Signed[EvictionVote] should encode to object, got: $json")
  }

  test("DIAGNOSTIC: bare EvictionCertificate encodes without throwing") {
    val cert = EvictionCertificate(
      targetPeer = targetA,
      reason = EvictionReason.Silent,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      votes = NonEmptySet.of(vote(targetA, EvictionReason.Silent, "diag"))
    )
    val json = cert.asJson
    expect(json.isObject, s"bare EvictionCertificate should encode to object, got: $json")
  }

  // === ConsensusPeerDeclaration wire-shape test ===

  test("JSON round-trip: ConsensusPeerDeclaration wrapping EvictionVote encodes via the spreadDirect path") {
    // Same wire shape that the gossip emission path hands to `spreadDirect`:
    // `ConsensusPeerDeclaration(key, EvictionVote(...))`. This is the combination that
    // would fail if the magnolia-derived encoder chain had a null forward-reference.
    val decl = ConsensusPeerDeclaration[Long, EvictionVote](
      key = 42L,
      declaration = EvictionVote(
        targetPeer = targetB,
        reason = EvictionReason.Silent,
        facilitatorsHash = facHash,
        lastSnapshotHash = lastSnap
      )
    )
    val json = decl.asJson
    expect(json.isObject, s"encoded wire payload must be a JSON object, got: $json")
  }

  // === Ordering / determinism tests ===

  test("EvictionVote ordering is deterministic across invocations") {
    val a = vote(targetA, EvictionReason.Silent, "v1").value
    val b = vote(targetB, EvictionReason.Silent, "v2").value
    val o1 = EvictionVote.ordering.compare(a, b)
    val o2 = EvictionVote.ordering.compare(a, b)
    val o3 = EvictionVote.ordering.compare(b, a)
    expect.same(o1, o2).and(expect(o1 === -o3, "ordering must be antisymmetric"))
  }

  test("EvictionCertificate ordering is deterministic across invocations") {
    val c1 = EvictionCertificate(
      targetPeer = targetA,
      reason = EvictionReason.Silent,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      votes = NonEmptySet.of(vote(targetA, EvictionReason.Silent, "v1"))
    )
    val c2 = EvictionCertificate(
      targetPeer = targetB,
      reason = EvictionReason.Silent,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      votes = NonEmptySet.of(vote(targetB, EvictionReason.Silent, "v2"))
    )
    val o1 = EvictionCertificate.ordering.compare(c1, c2)
    val o2 = EvictionCertificate.ordering.compare(c1, c2)
    expect.same(o1, o2).and(expect(o1 =!= 0, s"distinct certs must order distinctly, got: $o1"))
  }

  // === Proposal embedding regression tests ===
  //
  // Mirrors the ViewChangeCertificateSuite "Proposal with Some(vcc)" tests. The VCC NPE in April
  // came from the derived Encoder[Proposal] holding a null forward-reference to
  // Encoder[Signed[ViewChangeVote]] when Proposal was declared before VCC in the file. We fixed
  // the VCC case by reordering + explicit codecs; these tests lock in that the same pattern
  // holds for the new `evictionCertificates` field.

  private def cert(target: PeerId, voters: Int): EvictionCertificate =
    EvictionCertificate(
      targetPeer = target,
      reason = EvictionReason.Silent,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      votes = NonEmptySet.of(
        vote(target, EvictionReason.Silent, s"${target.value.value.take(4)}-v1"),
        (2 to voters).toList.map(i => vote(target, EvictionReason.Silent, s"${target.value.value.take(4)}-v$i")): _*
      )
    )

  test("JSON round-trip: Proposal with non-empty evictionCertificates encodes without throwing and decodes back equal") {
    val proposal = Proposal(
      hash = hashA,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      view = 0L,
      vcc = None,
      evictionCertificates = List(cert(targetA, voters = 3), cert(targetB, voters = 3))
    )
    val json = proposal.asJson
    val roundTripped = decode[Proposal](json.noSpaces)
    expect(roundTripped.exists(_ === proposal), s"round-trip must preserve Proposal with certs, got: $roundTripped").and(
      expect(
        json.hcursor.downField("evictionCertificates").as[List[EvictionCertificate]].exists(_.size === 2),
        "serialized JSON must carry the evictionCertificates array with 2 entries"
      )
    )
  }

  test("JSON round-trip: Proposal with empty evictionCertificates default encodes and decodes") {
    val proposal = Proposal(
      hash = hashA,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      view = 0L,
      vcc = None,
      evictionCertificates = List.empty
    )
    val json = proposal.asJson
    val roundTripped = decode[Proposal](json.noSpaces)
    expect(roundTripped.exists(_ === proposal), s"round-trip must preserve empty-certs Proposal, got: $roundTripped")
  }

  test("JSON round-trip: Proposal with BOTH vcc.some AND evictionCertificates works end-to-end") {
    // This is the critical case — both extension points active simultaneously, exercising the
    // full derived-encoder chain for Proposal.
    val dummyVcc = ViewChangeCertificate(
      fromView = 0L,
      toView = 1L,
      facilitatorsHash = facHash,
      votes = NonEmptySet.of(
        Signed(
          ViewChangeVote(0L, 1L, facHash, lastSnap, None),
          NonEmptySet.of(dummyProof("vccv1"))
        ),
        Signed(
          ViewChangeVote(0L, 1L, facHash, lastSnap, None),
          NonEmptySet.of(dummyProof("vccv2"))
        )
      )
    )
    val proposal = Proposal(
      hash = hashA,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      view = 1L,
      vcc = dummyVcc.some,
      evictionCertificates = List(cert(targetA, voters = 3))
    )
    val json = proposal.asJson
    val roundTripped = decode[Proposal](json.noSpaces)
    expect(roundTripped.exists(_ === proposal), s"round-trip must preserve Proposal-with-vcc-and-certs, got: $roundTripped")
  }

  test("JSON round-trip: ConsensusPeerDeclaration wrapping Proposal-with-evictionCertificates (spreadProposal wire shape)") {
    // The exact wire shape that `spreadProposal` hands to `gossip.spreadDirect`. This is what
    // triggered the VCC NPE on view>0 proposals. Lock this in for the eviction case.
    val decl = ConsensusPeerDeclaration[Long, Proposal](
      key = 99L,
      declaration = Proposal(
        hash = hashA,
        facilitatorsHash = facHash,
        lastSnapshotHash = lastSnap,
        view = 0L,
        vcc = None,
        evictionCertificates = List(cert(targetA, voters = 3))
      )
    )
    val json = decl.asJson
    expect(json.isObject, s"encoded wire payload must be a JSON object, got: $json")
  }

  test("JSON round-trip: ConsensusPeerEvictionVote wraps Signed[EvictionVote] (rumor wire shape)") {
    // The wire shape used by GossipingEvictionVoter.emitEvictionVote + RumorHandler.handleEvictionVote.
    val decl = ConsensusPeerEvictionVote[Long](
      key = 42L,
      vote = vote(targetA, EvictionReason.Silent, "wire-v1")
    )
    val json = decl.asJson
    val roundTripped = decode[ConsensusPeerEvictionVote[Long]](json.noSpaces)
    expect(
      roundTripped.exists(d => d.key === 42L && d.vote.value.targetPeer === targetA),
      s"round-trip must preserve ConsensusPeerEvictionVote, got: $roundTripped"
    )
  }
}
