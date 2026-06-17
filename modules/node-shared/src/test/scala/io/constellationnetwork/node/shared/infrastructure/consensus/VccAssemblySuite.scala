package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.data.NonEmptySet
import cats.effect.IO
import cats.syntax.all._

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.{ProposalQC, ViewChangeVote}
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.ViewChangeCertificateBuilder
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import io.chrisdavenport.mapref.MapRef
import weaver.{FunSuite, SimpleIOSuite}

/** Phase 2: integration-shaped tests around VoteLock, VCC assembly, and lock cleanup on recovery. The full ConsensusStorage wiring is
  * prohibitively heavy for unit tests (many type-class witnesses), so these suites drive the same invariants against the MapRef/VoteLock
  * machinery directly — identical in shape to how ConsensusStorage uses it.
  */

// ---------------------------------------------------------------------------------------------------
// DoubleSignRaceSuite: two concurrent vote-lock attempts can never both succeed for differing hashes.
// ---------------------------------------------------------------------------------------------------
object DoubleSignRaceSuite extends SimpleIOSuite {

  private val hashA: Hash = Hash.fromBytes("dsr_A".getBytes("UTF-8"))
  private val hashB: Hash = Hash.fromBytes("dsr_B".getBytes("UTF-8"))

  private def tryLockVote(
    voteLocksR: MapRef[IO, Long, Option[VoteLock]],
    key: Long,
    view: Long,
    proposalHash: Hash,
    effectiveLockedQc: Option[ProposalQC]
  ): IO[Either[VoteRejection, VoteLock]] =
    voteLocksR(key).modify { maybeLock =>
      val current = maybeLock.getOrElse(VoteLock.empty)
      current.acceptVote(view, proposalHash, effectiveLockedQc) match {
        case Right(newLock) => (newLock.some, Right(newLock))
        case Left(reason)   => (maybeLock, Left(reason))
      }
    }

  test("concurrent view-change races: VoteLock rejects the second signing attempt for a different hash at the same view") {
    MapRef.ofConcurrentHashMap[IO, Long, VoteLock]().flatMap { voteLocksR =>
      val key = 1L
      val viewN = 2L
      for {
        outcomes <- IO.both(
          tryLockVote(voteLocksR, key, viewN, hashA, None),
          tryLockVote(voteLocksR, key, viewN, hashB, None)
        )
        (r1, r2) = outcomes
        exactlyOne = r1.isRight ^ r2.isRight
      } yield expect(exactlyOne, s"expected exactly one concurrent lock to succeed, got r1=$r1 r2=$r2")
    }
  }

  test("assembled VCC can only come from one (fromView, toView) pair -- different transitions are isolated") {
    // Build votes for two different transitions; VCC.build must not mix them.
    val facHash = Hash.fromBytes("dsr_FAC".getBytes("UTF-8"))
    val lastSnap = Hash.fromBytes("dsr_LAST".getBytes("UTF-8"))
    val p1 = PeerId(Hex("aa" * 32))
    val p2 = PeerId(Hex("bb" * 32))

    // v17: VCC.build now dedups by signer (proofs.head.id.toPeerId). Each vote must carry a
    // distinct signer to count toward quorum; the storage-map key alone is not enough.
    def vote(fromV: Long, toV: Long, signerHex: String): Signed[ViewChangeVote] =
      Signed(
        ViewChangeVote(fromV, toV, facHash, lastSnap, None),
        NonEmptySet.of(SignatureProof(Id(Hex(signerHex)), Signature(Hex("00"))))
      )

    val votes01: Map[PeerId, Signed[ViewChangeVote]] = Map(p1 -> vote(0L, 1L, "aa" * 32), p2 -> vote(0L, 1L, "bb" * 32))
    val votes12: Map[PeerId, Signed[ViewChangeVote]] = Map(p1 -> vote(1L, 2L, "aa" * 32), p2 -> vote(1L, 2L, "bb" * 32))
    val pool: Set[PeerId] = Set(p1, p2)

    // Build 0->1 VCC
    val vcc01 = ViewChangeCertificateBuilder.build(0L, 1L, facHash, lastSnap, votes01, quorumSize = 2, witnessPool = pool)
    // Build 1->2 VCC
    val vcc12 = ViewChangeCertificateBuilder.build(1L, 2L, facHash, lastSnap, votes12, quorumSize = 2, witnessPool = pool)
    // Build 0->1 but pass the 1->2 votes (mismatched transition) -- must fail under_quorum because none match
    val mixed = ViewChangeCertificateBuilder.build(0L, 1L, facHash, lastSnap, votes12, quorumSize = 2, witnessPool = pool)

    IO.pure(
      expect(vcc01.isRight, s"0->1 VCC should succeed, got $vcc01")
        .and(expect(vcc12.isRight, s"1->2 VCC should succeed, got $vcc12"))
        .and(expect(mixed.isLeft, s"mixed-transition build must fail, got $mixed"))
    )
  }
}

// ---------------------------------------------------------------------------------------------------
// VccLateArrivalSuite: a VCC arriving after a node signed at view 0 only allows a higher-view vote
// when the leader's proposal hash matches a prior lock (or lock is empty).
// ---------------------------------------------------------------------------------------------------
object VccLateArrivalSuite extends SimpleIOSuite {

  private val hash1: Hash = Hash.fromBytes("late_P1".getBytes("UTF-8"))
  private val hash2: Hash = Hash.fromBytes("late_P2".getBytes("UTF-8"))
  private val facHash: Hash = Hash.fromBytes("late_FAC".getBytes("UTF-8"))

  private def qc(view: Long, proposalHash: Hash): ProposalQC =
    ProposalQC(view, proposalHash, facHash, NonEmptySet.of(SignatureProof(Id(Hex("00")), Signature(Hex("00")))))

  private def tryLockVote(
    voteLocksR: MapRef[IO, Long, Option[VoteLock]],
    key: Long,
    view: Long,
    proposalHash: Hash,
    effectiveLockedQc: Option[ProposalQC]
  ): IO[Either[VoteRejection, VoteLock]] =
    voteLocksR(key).modify { maybeLock =>
      val current = maybeLock.getOrElse(VoteLock.empty)
      current.acceptVote(view, proposalHash, effectiveLockedQc) match {
        case Right(newLock) => (newLock.some, Right(newLock))
        case Left(reason)   => (maybeLock, Left(reason))
      }
    }

  test("signed P1 at view 0, late VCC (highestQcInVcc=None) arrives: can re-sign at view 1 for any hash") {
    MapRef.ofConcurrentHashMap[IO, Long, VoteLock]().flatMap { voteLocksR =>
      val key = 10L
      for {
        // View 0: sign hash1.
        v0 <- tryLockVote(voteLocksR, key, view = 0L, proposalHash = hash1, effectiveLockedQc = None)
        // View 1 arrives. VCC has no highest QC → lockedQc stays None → vote for hash2 accepted.
        v1 <- tryLockVote(voteLocksR, key, view = 1L, proposalHash = hash2, effectiveLockedQc = None)
      } yield
        expect(v0.isRight, s"view 0 signing must succeed, got: $v0")
          .and(expect(v1.isRight, s"view 1 signing with empty-highest-QC VCC must succeed, got: $v1"))
    }
  }

  test("signed P1 at view 0, late VCC carries QC(view=0, hash=P1): can re-sign at view 1 only for P1") {
    MapRef.ofConcurrentHashMap[IO, Long, VoteLock]().flatMap { voteLocksR =>
      val key = 11L
      val lockOnP1 = qc(view = 0L, proposalHash = hash1)
      for {
        v0 <- tryLockVote(voteLocksR, key, view = 0L, proposalHash = hash1, effectiveLockedQc = None)
        // View 1 with VCC carrying QC on hash1; trying hash2 must fail.
        v1Bad <- tryLockVote(voteLocksR, key, view = 1L, proposalHash = hash2, effectiveLockedQc = lockOnP1.some)
        // Trying hash1 succeeds.
        v1Good <- tryLockVote(voteLocksR, key, view = 1L, proposalHash = hash1, effectiveLockedQc = lockOnP1.some)
      } yield
        expect(v0.isRight, s"view 0 signing must succeed, got: $v0")
          .and(expect(v1Bad.isLeft, s"view 1 signing for different hash than lockedQc must fail, got: $v1Bad"))
          .and(expect(v1Good.isRight, s"view 1 signing matching lockedQc must succeed, got: $v1Good"))
    }
  }
}

// ---------------------------------------------------------------------------------------------------
// ViewChangeAssemblySuite: structural invariants of ViewChangeCertificateBuilder.build.
// ---------------------------------------------------------------------------------------------------
object ViewChangeAssemblySuite extends FunSuite {

  private val facHash: Hash = Hash.fromBytes("vca_FAC".getBytes("UTF-8"))
  private val otherFacHash: Hash = Hash.fromBytes("vca_OTHER".getBytes("UTF-8"))
  private val lastSnap: Hash = Hash.fromBytes("vca_LAST".getBytes("UTF-8"))
  private val hashX: Hash = Hash.fromBytes("vca_X".getBytes("UTF-8"))
  private val hashY: Hash = Hash.fromBytes("vca_Y".getBytes("UTF-8"))

  private def proof(tag: String): SignatureProof =
    SignatureProof(Id(Hex(tag.getBytes("UTF-8").map(b => f"$b%02x").mkString)), Signature(Hex("00")))

  private def peer(tag: String): PeerId =
    PeerId(Hex(tag.getBytes("UTF-8").map(b => f"$b%02x").mkString.padTo(64, '0')))

  // v17: builder dedups by signer; the witness pool below references the signer hex (from `proof`),
  // not the storage-key hex (from `peer`). The two are intentionally distinct in this suite so
  // dedup-by-signer is exercised end-to-end.
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
    sigTag: String = "sig"
  ): Signed[ViewChangeVote] =
    Signed(
      ViewChangeVote(fromView, toView, facilitatorsHash, lastSnapshotHash, highestQc),
      NonEmptySet.of(proof(sigTag))
    )

  private val poolS123: Set[PeerId] = Set(signerPid("s1"), signerPid("s2"), signerPid("s3"))

  test("3-of-5 votes at matching (fromView, toView, facHash) yields Right(vcc) with 3 votes") {
    val votes: Map[PeerId, Signed[ViewChangeVote]] = Map(
      peer("p1") -> vote(0L, 1L, sigTag = "s1"),
      peer("p2") -> vote(0L, 1L, sigTag = "s2"),
      peer("p3") -> vote(0L, 1L, sigTag = "s3")
    )
    val result = ViewChangeCertificateBuilder.build(0L, 1L, facHash, lastSnap, votes, quorumSize = 3, witnessPool = poolS123)
    expect(result.isRight, s"3-of-5 build should succeed, got $result").and(
      expect(result.exists(_.votes.size === 3), s"VCC should contain 3 votes, got ${result.map(_.votes.size)}")
    )
  }

  test("2 votes under quorum=3 returns Left(under_quorum)") {
    val votes: Map[PeerId, Signed[ViewChangeVote]] = Map(
      peer("p1") -> vote(0L, 1L, sigTag = "s1"),
      peer("p2") -> vote(0L, 1L, sigTag = "s2")
    )
    val result = ViewChangeCertificateBuilder.build(0L, 1L, facHash, lastSnap, votes, quorumSize = 3, witnessPool = poolS123)
    expect(result.isLeft, s"should fail when under quorum, got $result").and(
      expect(result.swap.exists(_.code.startsWith("under_quorum")), s"error should start with under_quorum, got $result")
    )
  }

  test("vote carrying a different facilitatorsHash returns Left(facilitators_mismatch)") {
    val votes: Map[PeerId, Signed[ViewChangeVote]] = Map(
      peer("p1") -> vote(0L, 1L, facilitatorsHash = facHash, sigTag = "s1"),
      peer("p2") -> vote(0L, 1L, facilitatorsHash = facHash, sigTag = "s2"),
      peer("p3") -> vote(0L, 1L, facilitatorsHash = otherFacHash, sigTag = "s3")
    )
    val result = ViewChangeCertificateBuilder.build(0L, 1L, facHash, lastSnap, votes, quorumSize = 3, witnessPool = poolS123)
    expect(result.isLeft, s"should reject facilitators mismatch, got $result").and(
      expect(result.swap.exists(_.code.startsWith("facilitators_mismatch")), s"error should start with facilitators_mismatch, got $result")
    )
  }

  test("vote carrying a different lastSnapshotHash returns Left(last_snapshot_hash_mismatch)") {
    val otherLastSnap = Hash.fromBytes("vca_OTHER_LAST".getBytes("UTF-8"))
    val votes: Map[PeerId, Signed[ViewChangeVote]] = Map(
      peer("p1") -> vote(0L, 1L, lastSnapshotHash = lastSnap, sigTag = "s1"),
      peer("p2") -> vote(0L, 1L, lastSnapshotHash = lastSnap, sigTag = "s2"),
      peer("p3") -> vote(0L, 1L, lastSnapshotHash = otherLastSnap, sigTag = "s3")
    )
    val result = ViewChangeCertificateBuilder.build(0L, 1L, facHash, lastSnap, votes, quorumSize = 3, witnessPool = poolS123)
    expect(result.isLeft, s"should reject lastSnapshotHash mismatch, got $result").and(
      expect(
        result.swap.exists(_.code.startsWith("last_snapshot_hash_mismatch")),
        s"error should start with last_snapshot_hash_mismatch, got $result"
      )
    )
  }

  test("two votes carrying QCs at the same view with different hashes returns Left(divergent_qcs)") {
    val votes: Map[PeerId, Signed[ViewChangeVote]] = Map(
      peer("p1") -> vote(0L, 1L, highestQc = qc(view = 5L, proposalHash = hashX).some, sigTag = "s1"),
      peer("p2") -> vote(0L, 1L, highestQc = qc(view = 5L, proposalHash = hashY).some, sigTag = "s2"),
      peer("p3") -> vote(0L, 1L, sigTag = "s3")
    )
    val result = ViewChangeCertificateBuilder.build(0L, 1L, facHash, lastSnap, votes, quorumSize = 3, witnessPool = poolS123)
    expect(result.isLeft, s"should reject divergent QCs at the same view, got $result").and(
      expect(result.swap.exists(_.code === "divergent_qcs"), s"error should be divergent_qcs, got $result")
    )
  }

  test("v17: vote whose signer is not in witnessPool is silently filtered, under-quorum returned") {
    val votes: Map[PeerId, Signed[ViewChangeVote]] = Map(
      peer("p1") -> vote(0L, 1L, sigTag = "s1"),
      peer("p2") -> vote(0L, 1L, sigTag = "s2"),
      peer("p3") -> vote(0L, 1L, sigTag = "outOfPool")
    )
    // pool excludes "outOfPool" -> only s1 and s2 survive -> 2 < quorum 3
    val result = ViewChangeCertificateBuilder.build(0L, 1L, facHash, lastSnap, votes, quorumSize = 3, witnessPool = poolS123)
    expect(result.isLeft, s"out-of-pool signer must not count toward quorum, got $result").and(
      expect(result.swap.exists(_.code.startsWith("under_quorum")), s"error should start with under_quorum, got $result")
    )
  }
}

// ---------------------------------------------------------------------------------------------------
// RecoveryClearsLocksSuite: extends the existing lock-cleanup coverage with a
// clearAllConsensusState-style batch clear that wipes all lock entries across all keys.
// ---------------------------------------------------------------------------------------------------
object RecoveryClearsLocksSuite extends SimpleIOSuite {

  private val hashA: Hash = Hash.fromBytes("rec_A".getBytes("UTF-8"))
  private val hashB: Hash = Hash.fromBytes("rec_B".getBytes("UTF-8"))

  private def tryLockVote(
    voteLocksR: MapRef[IO, Long, Option[VoteLock]],
    key: Long,
    view: Long,
    proposalHash: Hash,
    effectiveLockedQc: Option[ProposalQC]
  ): IO[Either[VoteRejection, VoteLock]] =
    voteLocksR(key).modify { maybeLock =>
      val current = maybeLock.getOrElse(VoteLock.empty)
      current.acceptVote(view, proposalHash, effectiveLockedQc) match {
        case Right(newLock) => (newLock.some, Right(newLock))
        case Left(reason)   => (maybeLock, Left(reason))
      }
    }

  private def clearAll(voteLocksR: MapRef[IO, Long, Option[VoteLock]]): IO[Unit] =
    voteLocksR.keys.flatMap(_.traverse_(k => voteLocksR(k).set(none)))

  test("clearAllConsensusState equivalent: all locks are cleared; a new tryLockVote with a different hash succeeds") {
    MapRef.ofConcurrentHashMap[IO, Long, VoteLock]().flatMap { voteLocksR =>
      val key = 100L
      for {
        _ <- tryLockVote(voteLocksR, key, view = 2L, proposalHash = hashA, effectiveLockedQc = None)
        before <- voteLocksR(key).get
        _ <- clearAll(voteLocksR)
        after <- voteLocksR(key).get
        relock <- tryLockVote(voteLocksR, key, view = 2L, proposalHash = hashB, effectiveLockedQc = None)
      } yield
        expect(before.isDefined, s"initial lock must exist, got $before")
          .and(expect(after.isEmpty, s"after clear all locks must be empty, got $after"))
          .and(expect(relock.isRight, s"after clear, new lock with different hash should succeed, got $relock"))
    }
  }
}
