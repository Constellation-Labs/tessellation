package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.data.NonEmptySet
import cats.syntax.option._

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration._
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import weaver.FunSuite

object TimeoutCertificateBuilderSuite extends FunSuite {

  private val facHash: Hash = Hash.fromBytes("tc_FAC".getBytes("UTF-8"))
  private val otherFacHash: Hash = Hash.fromBytes("tc_OTHER".getBytes("UTF-8"))
  private val lastSnap: Hash = Hash.fromBytes("tc_LAST".getBytes("UTF-8"))
  private val hashX: Hash = Hash.fromBytes("tc_X".getBytes("UTF-8"))
  private val hashY: Hash = Hash.fromBytes("tc_Y".getBytes("UTF-8"))

  private def proof(tag: String): SignatureProof =
    SignatureProof(Id(Hex(tag.getBytes("UTF-8").map(b => f"$b%02x").mkString)), Signature(Hex("00")))

  private def peer(tag: String): PeerId =
    PeerId(Hex(tag.getBytes("UTF-8").map(b => f"$b%02x").mkString.padTo(64, '0')))

  private def signerPid(tag: String): PeerId =
    PeerId(Hex(tag.getBytes("UTF-8").map(b => f"$b%02x").mkString))

  private def qc(view: Long, proposalHash: Hash, fh: Hash = facHash): ProposalQC =
    ProposalQC(view, proposalHash, fh, NonEmptySet.of(proof("qsig")))

  private def vote(
    fromView: Long,
    toView: Long,
    facilitatorsHash: Hash = facHash,
    lastSnapshotHash: Hash = lastSnap,
    highestQc: Option[ProposalQC] = None,
    reason: TimeoutReason = TimeoutReason.NoProgress,
    sigTag: String
  ): Signed[TimeoutVote] =
    Signed(
      TimeoutVote(fromView, toView, facilitatorsHash, lastSnapshotHash, highestQc, reason),
      NonEmptySet.of(proof(sigTag))
    )

  private val poolS123: Set[PeerId] = Set(signerPid("s1"), signerPid("s2"), signerPid("s3"))

  test("matching quorum builds timeout certificate") {
    val votes: Map[PeerId, Signed[TimeoutVote]] = Map(
      peer("p1") -> vote(0L, 1L, sigTag = "s1"),
      peer("p2") -> vote(0L, 1L, sigTag = "s2"),
      peer("p3") -> vote(0L, 1L, sigTag = "s3")
    )

    val result =
      TimeoutCertificateBuilder.build(0L, 1L, facHash, lastSnap, TimeoutReason.NoProgress, votes, quorumSize = 3, witnessPool = poolS123)

    expect(result.isRight, s"3-of-3 timeout cert should build, got $result").and(
      expect(result.exists(_.votes.length == 3), s"TC should contain 3 votes, got ${result.map(_.votes.length)}")
    )
  }

  test("under-quorum votes fail") {
    val votes: Map[PeerId, Signed[TimeoutVote]] = Map(
      peer("p1") -> vote(0L, 1L, sigTag = "s1"),
      peer("p2") -> vote(0L, 1L, sigTag = "s2")
    )

    val result =
      TimeoutCertificateBuilder.build(0L, 1L, facHash, lastSnap, TimeoutReason.NoProgress, votes, quorumSize = 3, witnessPool = poolS123)

    expect(result.swap.exists(_.code.startsWith("under_quorum")), s"expected under_quorum, got $result")
  }

  test("mixed parent hash fails") {
    val otherLastSnap = Hash.fromBytes("tc_OTHER_LAST".getBytes("UTF-8"))
    val votes: Map[PeerId, Signed[TimeoutVote]] = Map(
      peer("p1") -> vote(0L, 1L, lastSnapshotHash = lastSnap, sigTag = "s1"),
      peer("p2") -> vote(0L, 1L, lastSnapshotHash = lastSnap, sigTag = "s2"),
      peer("p3") -> vote(0L, 1L, lastSnapshotHash = otherLastSnap, sigTag = "s3")
    )

    val result =
      TimeoutCertificateBuilder.build(0L, 1L, facHash, lastSnap, TimeoutReason.NoProgress, votes, quorumSize = 3, witnessPool = poolS123)

    expect(result.swap.exists(_.code.startsWith("last_snapshot_hash_mismatch")), s"expected parent hash mismatch, got $result")
  }

  test("mixed facilitator hash fails") {
    val votes: Map[PeerId, Signed[TimeoutVote]] = Map(
      peer("p1") -> vote(0L, 1L, facilitatorsHash = facHash, sigTag = "s1"),
      peer("p2") -> vote(0L, 1L, facilitatorsHash = facHash, sigTag = "s2"),
      peer("p3") -> vote(0L, 1L, facilitatorsHash = otherFacHash, sigTag = "s3")
    )

    val result =
      TimeoutCertificateBuilder.build(0L, 1L, facHash, lastSnap, TimeoutReason.NoProgress, votes, quorumSize = 3, witnessPool = poolS123)

    expect(result.swap.exists(_.code.startsWith("facilitators_mismatch")), s"expected facilitators mismatch, got $result")
  }

  test("mixed timeout reason fails") {
    val votes: Map[PeerId, Signed[TimeoutVote]] = Map(
      peer("p1") -> vote(0L, 1L, reason = TimeoutReason.NoProgress, sigTag = "s1"),
      peer("p2") -> vote(0L, 1L, reason = TimeoutReason.NoProgress, sigTag = "s2"),
      peer("p3") -> vote(0L, 1L, reason = TimeoutReason.QuorumInfeasible, sigTag = "s3")
    )

    val result =
      TimeoutCertificateBuilder.build(0L, 1L, facHash, lastSnap, TimeoutReason.NoProgress, votes, quorumSize = 3, witnessPool = poolS123)

    expect(result.swap.exists(_.code.startsWith("reason_mismatch")), s"expected reason mismatch, got $result")
  }

  test("divergent highest QC fails") {
    val votes: Map[PeerId, Signed[TimeoutVote]] = Map(
      peer("p1") -> vote(0L, 1L, highestQc = qc(view = 5L, proposalHash = hashX).some, sigTag = "s1"),
      peer("p2") -> vote(0L, 1L, highestQc = qc(view = 5L, proposalHash = hashY).some, sigTag = "s2"),
      peer("p3") -> vote(0L, 1L, sigTag = "s3")
    )

    val result =
      TimeoutCertificateBuilder.build(0L, 1L, facHash, lastSnap, TimeoutReason.NoProgress, votes, quorumSize = 3, witnessPool = poolS123)

    expect(result.swap.exists(_.code == "divergent_qcs"), s"expected divergent_qcs, got $result")
  }
}
