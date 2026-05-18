package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.data.NonEmptySet
import cats.syntax.all._

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.{AdmissionReason, AdmissionVote}
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import weaver.FunSuite

// Mirror of EvictionCertificateBuilderSuite. Same invariant surface, symmetric code path.
object AdmissionCertificateBuilderSuite extends FunSuite {

  private val facHash: Hash = Hash.fromBytes("FAC".getBytes("UTF-8"))
  private val otherFacHash: Hash = Hash.fromBytes("OTHER_FAC".getBytes("UTF-8"))
  private val lastSnap: Hash = Hash.fromBytes("LAST".getBytes("UTF-8"))

  private val voter1: PeerId = PeerId(Hex("01" * 64))
  private val voter2: PeerId = PeerId(Hex("02" * 64))
  private val voter3: PeerId = PeerId(Hex("03" * 64))
  private val voter4: PeerId = PeerId(Hex("04" * 64))
  private val voter5: PeerId = PeerId(Hex("05" * 64))

  private val targetA: PeerId = PeerId(Hex("aa" * 64))
  private val targetB: PeerId = PeerId(Hex("bb" * 64))

  private val committee: Set[PeerId] = Set(voter1, voter2, voter3, voter4, voter5)

  private def dummyProof(pid: PeerId): SignatureProof =
    SignatureProof(Id(pid.value), Signature(Hex("00")))

  private def signedVote(
    voter: PeerId,
    target: PeerId = targetA,
    reason: AdmissionReason = AdmissionReason.ReadyAtTip,
    facHashOverride: Hash = facHash,
    lastSnapOverride: Hash = lastSnap
  ): Signed[AdmissionVote] =
    Signed(
      AdmissionVote(
        targetPeer = target,
        reason = reason,
        facilitatorsHash = facHashOverride,
        lastSnapshotHash = lastSnapOverride
      ),
      NonEmptySet.of(dummyProof(voter))
    )

  test("build: votes >= quorum, all matching, all voters in committee -> Right(cert)") {
    val votes = Map(
      voter1 -> signedVote(voter1),
      voter2 -> signedVote(voter2),
      voter3 -> signedVote(voter3)
    )
    val result = AdmissionCertificateBuilder.build(
      target = targetA,
      reason = AdmissionReason.ReadyAtTip,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      votes = votes,
      quorumSize = 3,
      witnessPool = committee
    )
    expect(result.isRight, s"expected Right(cert), got: $result")
      .and(expect(result.exists(_.votes.length === 3), s"expected 3 votes, got: $result"))
      .and(expect(result.exists(_.targetPeer === targetA), s"expected target=$targetA, got: $result"))
  }

  test("build: one vote has wrong lastSnapshotHash -> Left(last_snapshot_hash_mismatch)") {
    val otherLastSnap: Hash = Hash.fromBytes("STALE".getBytes("UTF-8"))
    val votes = Map(
      voter1 -> signedVote(voter1),
      voter2 -> signedVote(voter2, lastSnapOverride = otherLastSnap),
      voter3 -> signedVote(voter3)
    )
    val result = AdmissionCertificateBuilder.build(targetA, AdmissionReason.ReadyAtTip, facHash, lastSnap, votes, 3, committee)
    expect(
      result.swap.exists(_.code.startsWith("last_snapshot_hash_mismatch")),
      s"expected Left(last_snapshot_hash_mismatch...), got: $result"
    )
  }

  test("build: votes < quorum -> Left(under_quorum)") {
    val votes = Map(
      voter1 -> signedVote(voter1),
      voter2 -> signedVote(voter2)
    )
    val result = AdmissionCertificateBuilder.build(targetA, AdmissionReason.ReadyAtTip, facHash, lastSnap, votes, 3, committee)
    expect(result.swap.exists(_.code.startsWith("under_quorum")), s"expected Left(under_quorum...), got: $result")
  }

  test("build: empty votes -> Left(under_quorum)") {
    val result = AdmissionCertificateBuilder.build(targetA, AdmissionReason.ReadyAtTip, facHash, lastSnap, Map.empty, 1, committee)
    expect(result.swap.exists(_.code.startsWith("under_quorum")), s"expected Left(under_quorum...), got: $result")
  }

  test("build: one vote targets different peer -> Left(target_mismatch)") {
    val votes = Map(
      voter1 -> signedVote(voter1, target = targetA),
      voter2 -> signedVote(voter2, target = targetB),
      voter3 -> signedVote(voter3, target = targetA)
    )
    val result = AdmissionCertificateBuilder.build(targetA, AdmissionReason.ReadyAtTip, facHash, lastSnap, votes, 2, committee)
    expect(result.swap.exists(_.code.startsWith("target_mismatch")), s"expected Left(target_mismatch...), got: $result")
  }

  test("build: one vote has wrong facilitatorsHash -> Left(facilitators_mismatch)") {
    val votes = Map(
      voter1 -> signedVote(voter1),
      voter2 -> signedVote(voter2, facHashOverride = otherFacHash),
      voter3 -> signedVote(voter3)
    )
    val result = AdmissionCertificateBuilder.build(targetA, AdmissionReason.ReadyAtTip, facHash, lastSnap, votes, 3, committee)
    expect(
      result.swap.exists(_.code.startsWith("facilitators_mismatch")),
      s"expected Left(facilitators_mismatch...), got: $result"
    )
  }

  test("build: outsider vote silently dropped, remaining < quorum -> Left(under_quorum)") {
    // Non-pool signers are filtered, not rejected (hotfix). With voter1+voter2
    // counting and the outsider dropped, 2 < quorum=3, so the failure is under_quorum.
    val outsider: PeerId = PeerId(Hex("ff" * 64))
    val votes = Map(
      voter1 -> signedVote(voter1),
      voter2 -> signedVote(voter2),
      outsider -> signedVote(outsider)
    )
    val result = AdmissionCertificateBuilder.build(targetA, AdmissionReason.ReadyAtTip, facHash, lastSnap, votes, 3, committee)
    expect(
      result.swap.exists(_.code.startsWith("under_quorum")),
      s"expected Left(under_quorum...) after outsider filtered, got: $result"
    )
  }

  test("build: same inputs produce identical certificate across invocations (determinism)") {
    val votes = Map(
      voter1 -> signedVote(voter1),
      voter2 -> signedVote(voter2),
      voter3 -> signedVote(voter3),
      voter4 -> signedVote(voter4)
    )
    val r1 = AdmissionCertificateBuilder.build(targetA, AdmissionReason.ReadyAtTip, facHash, lastSnap, votes, 3, committee)
    val r2 = AdmissionCertificateBuilder.build(targetA, AdmissionReason.ReadyAtTip, facHash, lastSnap, votes, 3, committee)
    expect(r1 === r2, s"expected identical results, got: $r1 vs $r2")
  }

  test("build: reordered input Map produces identical certificate (order-independent)") {
    val votesAsc = Map(
      voter1 -> signedVote(voter1),
      voter2 -> signedVote(voter2),
      voter3 -> signedVote(voter3)
    )
    val votesDesc = Map(
      voter3 -> signedVote(voter3),
      voter2 -> signedVote(voter2),
      voter1 -> signedVote(voter1)
    )
    val r1 = AdmissionCertificateBuilder.build(targetA, AdmissionReason.ReadyAtTip, facHash, lastSnap, votesAsc, 3, committee)
    val r2 = AdmissionCertificateBuilder.build(targetA, AdmissionReason.ReadyAtTip, facHash, lastSnap, votesDesc, 3, committee)
    expect(r1 === r2, s"expected identical certs regardless of input Map ordering, got: $r1 vs $r2")
  }

  test("build: votes exactly equals quorum -> Right(cert)") {
    val votes = Map(
      voter1 -> signedVote(voter1),
      voter2 -> signedVote(voter2)
    )
    val result = AdmissionCertificateBuilder.build(targetA, AdmissionReason.ReadyAtTip, facHash, lastSnap, votes, 2, committee)
    expect(result.isRight, s"exactly-quorum should succeed, got: $result")
  }

  test("build: relayed duplicates of same signed vote count as one") {
    // Same regression as B1: a single signed vote relayed under multiple storage keys must not
    // inflate the quorum count. The dedup happens at NonEmptySet[Signed[...]] assembly.
    val theVote: Signed[AdmissionVote] = signedVote(voter1)
    val votes: Map[PeerId, Signed[AdmissionVote]] = Map(
      voter1 -> theVote,
      voter2 -> theVote,
      voter3 -> theVote
    )
    val result = AdmissionCertificateBuilder.build(targetA, AdmissionReason.ReadyAtTip, facHash, lastSnap, votes, 3, committee)
    expect(
      result.swap.exists(_.code.startsWith("under_quorum")),
      s"expected Left(under_quorum...) for 3 relayed duplicates of 1 signed vote, got: $result"
    )
  }

  // === Witness pool widened from committee to eligibleFacilitators ===

  test("v9: voter in wider witness pool but outside committee subset is accepted") {
    // B2 mirror of the apr29 wedge regression. Symmetric with B1: when the cluster has chronic-
    // classified peers in eligibleFacilitators but not in committee, those peers' admission
    // votes count toward quorum.
    val committeeSubset: Set[PeerId] = Set(voter1, voter2, voter3, voter4)
    val eligibleNonCommittee: Set[PeerId] = Set(voter5)
    val widerPool: Set[PeerId] = committeeSubset ++ eligibleNonCommittee
    val votes: Map[PeerId, Signed[AdmissionVote]] = Map(
      voter1 -> signedVote(voter1),
      voter2 -> signedVote(voter2),
      voter3 -> signedVote(voter3),
      voter4 -> signedVote(voter4),
      voter5 -> signedVote(voter5) // eligible-but-non-committee — must count under v9
    )
    val result =
      AdmissionCertificateBuilder.build(
        targetA,
        AdmissionReason.ReadyAtTip,
        facHash,
        lastSnap,
        votes,
        quorumSize = 5,
        witnessPool = widerPool
      )
    expect(result.isRight, s"v9: eligible-but-non-committee voter should count, got: $result").and(
      expect(result.exists(_.votes.length === 5), s"cert must carry 5 votes, got: $result")
    )
  }

  test("v9: voter outside witness pool is silently filtered (does not count toward quorum)") {
    // Symmetric guarantee (hotfix) -- outside-pool voters are dropped silently,
    // but only pool members count toward quorum, so the cert still fails when the remaining
    // valid set is short.
    val outsider: PeerId = PeerId(Hex("ee" * 64))
    val widerPool: Set[PeerId] = Set(voter1, voter2, voter3, voter4, voter5)
    val votes: Map[PeerId, Signed[AdmissionVote]] = Map(
      voter1 -> signedVote(voter1),
      voter2 -> signedVote(voter2),
      outsider -> signedVote(outsider)
    )
    val result =
      AdmissionCertificateBuilder.build(
        targetA,
        AdmissionReason.ReadyAtTip,
        facHash,
        lastSnap,
        votes,
        quorumSize = 3,
        witnessPool = widerPool
      )
    expect(
      result.swap.exists(_.code.startsWith("under_quorum")),
      s"v9+v15: outside-pool voter is filtered; remaining 2 < quorum=3, got: $result"
    )
  }

  // === Hotfix regression -- single rogue voter must not deadlock cert assembly ===

  test("v15: outsider vote silently dropped when remaining pool members exactly meet quorum") {
    // Symmetric to EvictionCertificateBuilderSuite. Mid-round eligibility shrinkage that
    // dropped one voter from the witness pool used to deadlock the entire cert assembly.
    val outsider: PeerId = PeerId(Hex("dd" * 64))
    val pool: Set[PeerId] = Set(voter1, voter2, voter3, targetA)
    val votes: Map[PeerId, Signed[AdmissionVote]] = Map(
      voter1 -> signedVote(voter1),
      voter2 -> signedVote(voter2),
      voter3 -> signedVote(voter3),
      outsider -> signedVote(outsider)
    )
    val result =
      AdmissionCertificateBuilder.build(
        targetA,
        AdmissionReason.ReadyAtTip,
        facHash,
        lastSnap,
        votes,
        quorumSize = 3,
        witnessPool = pool
      )
    expect(result.isRight, s"v15: outsider must not block a quorum-met cert, got: $result").and(
      expect(result.exists(_.votes.length === 3), s"cert must carry exactly the 3 pool members, got: $result")
    )
  }
}
