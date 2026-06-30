package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.data.NonEmptySet
import cats.syntax.all._

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.{EvictionReason, EvictionVote}
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import weaver.FunSuite

object EvictionCertificateBuilderSuite extends FunSuite {

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

  private val committee: Set[PeerId] = Set(voter1, voter2, voter3, voter4, voter5, targetA, targetB)

  private def dummyProof(pid: PeerId): SignatureProof =
    SignatureProof(Id(pid.value), Signature(Hex("00")))

  private def signedVote(
    voter: PeerId,
    target: PeerId = targetA,
    reason: EvictionReason = EvictionReason.Silent,
    facHashOverride: Hash = facHash,
    lastSnapOverride: Hash = lastSnap
  ): Signed[EvictionVote] =
    Signed(
      EvictionVote(
        targetPeer = target,
        reason = reason,
        facilitatorsHash = facHashOverride,
        lastSnapshotHash = lastSnapOverride
      ),
      NonEmptySet.of(dummyProof(voter))
    )

  private def votesFromMap(m: Map[PeerId, Signed[EvictionVote]]): Map[PeerId, Signed[EvictionVote]] = m

  // === Happy path ===

  test("build: votes >= quorum, all matching, all voters in committee -> Right(cert)") {
    val votes = votesFromMap(
      Map(
        voter1 -> signedVote(voter1),
        voter2 -> signedVote(voter2),
        voter3 -> signedVote(voter3)
      )
    )
    val result = EvictionCertificateBuilder.build(
      target = targetA,
      reason = EvictionReason.Silent,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      votes = votes,
      quorumSize = 3,
      witnessPool = committee
    )
    expect(result.isRight, s"expected Right(cert), got: $result")
      .and(
        expect(result.exists(_.votes.length === 3), s"expected 3 votes, got: $result")
      )
      .and(
        expect(result.exists(_.targetPeer === targetA), s"expected target=$targetA, got: $result")
      )
  }

  // === Under quorum ===

  test("build: votes < quorum -> Left(under_quorum)") {
    val votes = votesFromMap(
      Map(
        voter1 -> signedVote(voter1),
        voter2 -> signedVote(voter2)
      )
    )
    val result = EvictionCertificateBuilder.build(
      target = targetA,
      reason = EvictionReason.Silent,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      votes = votes,
      quorumSize = 3,
      witnessPool = committee
    )
    expect(result.swap.exists(_.code.startsWith("under_quorum")), s"expected Left(under_quorum...), got: $result")
  }

  test("build: empty votes -> Left(under_quorum)") {
    val result = EvictionCertificateBuilder.build(
      target = targetA,
      reason = EvictionReason.Silent,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      votes = Map.empty,
      quorumSize = 1,
      witnessPool = committee
    )
    expect(result.swap.exists(_.code.startsWith("under_quorum")), s"expected Left(under_quorum...), got: $result")
  }

  // === Mismatched fields ===

  test("build: one vote targets different peer -> Left(target_mismatch)") {
    val votes = votesFromMap(
      Map(
        voter1 -> signedVote(voter1, target = targetA),
        voter2 -> signedVote(voter2, target = targetB), // wrong target
        voter3 -> signedVote(voter3, target = targetA)
      )
    )
    val result = EvictionCertificateBuilder.build(
      target = targetA,
      reason = EvictionReason.Silent,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      votes = votes,
      quorumSize = 2,
      witnessPool = committee
    )
    expect(result.swap.exists(_.code.startsWith("target_mismatch")), s"expected Left(target_mismatch...), got: $result")
  }

  test("build: one vote has wrong lastSnapshotHash -> Left(last_snapshot_hash_mismatch)") {
    // Codex-flagged replay hazard: even when facilitatorsHash matches, a vote from an earlier
    // tip must not be stitched into a fresh-looking cert.
    val staleSnap: Hash = Hash.fromBytes("STALE".getBytes("UTF-8"))
    val votes = votesFromMap(
      Map(
        voter1 -> signedVote(voter1),
        voter2 -> signedVote(voter2, lastSnapOverride = staleSnap),
        voter3 -> signedVote(voter3)
      )
    )
    val result = EvictionCertificateBuilder.build(targetA, EvictionReason.Silent, facHash, lastSnap, votes, 3, committee)
    expect(
      result.swap.exists(_.code.startsWith("last_snapshot_hash_mismatch")),
      s"expected Left(last_snapshot_hash_mismatch...), got: $result"
    )
  }

  test("build: one vote has wrong facilitatorsHash -> Left(facilitators_mismatch)") {
    val votes = votesFromMap(
      Map(
        voter1 -> signedVote(voter1),
        voter2 -> signedVote(voter2, facHashOverride = otherFacHash), // fork-peer
        voter3 -> signedVote(voter3)
      )
    )
    val result = EvictionCertificateBuilder.build(
      target = targetA,
      reason = EvictionReason.Silent,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      votes = votes,
      quorumSize = 3,
      witnessPool = committee
    )
    expect(result.swap.exists(_.code.startsWith("facilitators_mismatch")), s"expected Left(facilitators_mismatch...), got: $result")
  }

  // === Committee membership ===

  test("build: outsider vote silently dropped, remaining < quorum -> Left(under_quorum)") {
    // Non-pool signers are filtered, not rejected (hotfix). With voter1+voter2
    // counting and the outsider dropped, 2 < quorum=3, so the failure is under_quorum.
    val outsider: PeerId = PeerId(Hex("ff" * 64))
    val votes = votesFromMap(
      Map(
        voter1 -> signedVote(voter1),
        voter2 -> signedVote(voter2),
        outsider -> signedVote(outsider)
      )
    )
    val result = EvictionCertificateBuilder.build(
      target = targetA,
      reason = EvictionReason.Silent,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      votes = votes,
      quorumSize = 3,
      witnessPool = committee
    )
    expect(
      result.swap.exists(_.code.startsWith("under_quorum")),
      s"expected Left(under_quorum...) after outsider filtered, got: $result"
    )
  }

  // === Determinism ===

  test("build: same inputs produce identical certificate across invocations") {
    val votes = votesFromMap(
      Map(
        voter1 -> signedVote(voter1),
        voter2 -> signedVote(voter2),
        voter3 -> signedVote(voter3),
        voter4 -> signedVote(voter4)
      )
    )
    val r1 = EvictionCertificateBuilder.build(targetA, EvictionReason.Silent, facHash, lastSnap, votes, 3, committee)
    val r2 = EvictionCertificateBuilder.build(targetA, EvictionReason.Silent, facHash, lastSnap, votes, 3, committee)
    expect(r1 === r2, s"expected identical results, got: $r1 vs $r2")
  }

  test("build: reordered input Map produces identical certificate (order-independent)") {
    // Build two maps with the same entries in different insertion order. Map iteration
    // order can vary, so the builder must normalize via its SortedSet conversion.
    val votesAsc = votesFromMap(
      Map(
        voter1 -> signedVote(voter1),
        voter2 -> signedVote(voter2),
        voter3 -> signedVote(voter3)
      )
    )
    val votesDesc = votesFromMap(
      Map(
        voter3 -> signedVote(voter3),
        voter2 -> signedVote(voter2),
        voter1 -> signedVote(voter1)
      )
    )
    val r1 = EvictionCertificateBuilder.build(targetA, EvictionReason.Silent, facHash, lastSnap, votesAsc, 3, committee)
    val r2 = EvictionCertificateBuilder.build(targetA, EvictionReason.Silent, facHash, lastSnap, votesDesc, 3, committee)
    expect(r1 === r2, s"expected identical certs regardless of input Map ordering, got: $r1 vs $r2")
  }

  // === Boundary: quorum exactly met ===

  test("build: votes exactly equals quorum -> Right(cert)") {
    val votes = votesFromMap(
      Map(
        voter1 -> signedVote(voter1),
        voter2 -> signedVote(voter2)
      )
    )
    val result = EvictionCertificateBuilder.build(
      target = targetA,
      reason = EvictionReason.Silent,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      votes = votes,
      quorumSize = 2,
      witnessPool = committee
    )
    expect(result.isRight, s"exactly-quorum should succeed, got: $result")
  }

  // === Regression: relayed duplicates of the same signed vote must not inflate quorum ===

  test("build: relayed duplicates of same signed vote count as one (codex review #1)") {
    // A single signed vote by `voter1` stored under three different storage keys
    // (origin1, origin2, origin3) — as happens when multiple peers relay the same gossip.
    // The builder keys `votes` by storage slot (gossip origin), not by signer. Naively
    // counting map entries would see 3 votes and cross quorum=3, but the deduplicated
    // NonEmptySet[Signed[EvictionVote]] would carry only 1 signer, producing a cert
    // that followers would reject at `validateProposalEcs`.
    val theVote: Signed[EvictionVote] = signedVote(voter1)
    val votes: Map[PeerId, Signed[EvictionVote]] = Map(
      voter1 -> theVote,
      voter2 -> theVote, // same signed bytes relayed with different storage key
      voter3 -> theVote // and again
    )
    val result = EvictionCertificateBuilder.build(
      target = targetA,
      reason = EvictionReason.Silent,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      votes = votes,
      quorumSize = 3,
      witnessPool = committee
    )
    expect(
      result.swap.exists(_.code.startsWith("under_quorum")),
      s"expected Left(under_quorum...) when 3 relayed duplicates of 1 signed vote present, got: $result"
    )
  }

  test("build: relay-duplicates with genuine independent votes — counts unique signers") {
    // voter1's signed vote has been relayed by voter2 (so it appears under two storage keys),
    // voter3 has independently cast their own signed vote.
    // Unique signers: {voter1, voter3}. Quorum=3 must NOT be reached.
    // Quorum=2 SHOULD be reached.
    val vote1: Signed[EvictionVote] = signedVote(voter1)
    val vote3: Signed[EvictionVote] = signedVote(voter3)
    val votes: Map[PeerId, Signed[EvictionVote]] = Map(
      voter1 -> vote1,
      voter2 -> vote1, // relay of voter1's vote
      voter3 -> vote3
    )
    val underQuorum =
      EvictionCertificateBuilder.build(targetA, EvictionReason.Silent, facHash, lastSnap, votes, quorumSize = 3, witnessPool = committee)
    val exactlyMet =
      EvictionCertificateBuilder.build(targetA, EvictionReason.Silent, facHash, lastSnap, votes, quorumSize = 2, witnessPool = committee)
    expect(
      underQuorum.swap.exists(_.code.startsWith("under_quorum")),
      s"quorum=3 with 2 unique signers must fail, got: $underQuorum"
    ).and(
      expect(exactlyMet.isRight, s"quorum=2 with 2 unique signers must succeed, got: $exactlyMet")
    ).and(
      expect(exactlyMet.exists(_.votes.length === 2), s"cert must carry exactly 2 deduplicated votes, got: $exactlyMet")
    )
  }

  // === Witness pool widened from committee to eligibleFacilitators ===

  test("v9: voter in witness pool but outside committee subset is accepted (apr29 wedge regression)") {
    // Apr 29 testnet wedge regression. At round 3110065 the cluster had committee=9 but
    // eligibleFacilitators=12. Three chronic-classified peers (in eligibleFacilitators, not in
    // committee) emitted valid eviction votes against cd6362ae. The OLD builder rejected them
    // as voter_not_in_committee even though they were structurally legitimate. Under the v9
    // widening, those votes count.
    //
    // Setup: 9-member "committee" subset + 3 peers in the wider witness pool. Quorum is
    // committee-sized (q=ceil(9*0.67)=7). Votes from 4 committee + 3 eligible-but-not-committee
    // = 7 distinct signers ≥ q=7 → cert assembles.
    val committeeSubset: Set[PeerId] = Set(voter1, voter2, voter3, voter4)
    val eligibleNonCommittee: Set[PeerId] = Set(voter5)
    val widerPool: Set[PeerId] = committeeSubset ++ eligibleNonCommittee + targetA + targetB
    val votes: Map[PeerId, Signed[EvictionVote]] = Map(
      voter1 -> signedVote(voter1),
      voter2 -> signedVote(voter2),
      voter3 -> signedVote(voter3),
      voter4 -> signedVote(voter4),
      voter5 -> signedVote(voter5) // eligible-but-non-committee — must count under v9
    )
    val result =
      EvictionCertificateBuilder.build(targetA, EvictionReason.Silent, facHash, lastSnap, votes, quorumSize = 5, witnessPool = widerPool)
    expect(result.isRight, s"v9: eligible-but-non-committee voter should count, got: $result").and(
      expect(result.exists(_.votes.length === 5), s"cert must carry 5 votes including the eligible non-committee one, got: $result")
    )
  }

  test("v9: voter outside witness pool is silently filtered (does not count toward quorum)") {
    // Symmetric guarantee: a peer in NEITHER the committee nor the wider eligible set must not
    // count. The hotfix drops it silently rather than rejecting the cert outright,
    // but the security envelope is identical -- only pool members count.
    val outsider: PeerId = PeerId(Hex("ee" * 64))
    val widerPool: Set[PeerId] = Set(voter1, voter2, voter3, voter4, voter5)
    val votes: Map[PeerId, Signed[EvictionVote]] = Map(
      voter1 -> signedVote(voter1),
      voter2 -> signedVote(voter2),
      outsider -> signedVote(outsider) // outside even the eligible pool
    )
    val result =
      EvictionCertificateBuilder.build(targetA, EvictionReason.Silent, facHash, lastSnap, votes, quorumSize = 3, witnessPool = widerPool)
    expect(
      result.swap.exists(_.code.startsWith("under_quorum")),
      s"v9+v15: outside-pool voter is filtered; remaining 2 < quorum=3, got: $result"
    )
  }

  // === Hotfix regression -- single rogue voter must not deadlock cert assembly ===

  test("v15: outsider vote silently dropped when remaining pool members exactly meet quorum") {
    // Testnet wedge regression. At ord 3121873 the cluster had 6 valid signers AND
    // 1 voter outside the witness pool (mid-round eligibility shrinkage). The pre-hotfix builder
    // returned voter_not_in_committee on the first non-pool voter, throwing away 6 valid votes
    // and stalling consensus indefinitely. The fix filters non-pool signers silently; if the
    // remaining set still meets quorum, the cert assembles.
    val outsider: PeerId = PeerId(Hex("dd" * 64))
    val pool: Set[PeerId] = Set(voter1, voter2, voter3, targetA, targetB)
    val votes: Map[PeerId, Signed[EvictionVote]] = Map(
      voter1 -> signedVote(voter1),
      voter2 -> signedVote(voter2),
      voter3 -> signedVote(voter3),
      outsider -> signedVote(outsider) // a single rogue gossiper used to wedge the whole cert
    )
    val result =
      EvictionCertificateBuilder.build(targetA, EvictionReason.Silent, facHash, lastSnap, votes, quorumSize = 3, witnessPool = pool)
    expect(result.isRight, s"v15: outsider must not block a quorum-met cert, got: $result").and(
      expect(result.exists(_.votes.length === 3), s"cert must carry exactly the 3 pool members, got: $result")
    )
  }
}
