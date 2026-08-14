package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.data.NonEmptySet
import cats.syntax.all._

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.ProposalQC
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import weaver.FunSuite

object VoteLockSuite extends FunSuite {

  private val hashA: Hash = Hash.fromBytes("A".getBytes("UTF-8"))
  private val hashB: Hash = Hash.fromBytes("B".getBytes("UTF-8"))
  private val hashC: Hash = Hash.fromBytes("C".getBytes("UTF-8"))
  private val facHash: Hash = Hash.fromBytes("FAC".getBytes("UTF-8"))

  private def dummyProof(tag: String): SignatureProof =
    SignatureProof(Id(Hex(tag.getBytes("UTF-8").map(b => f"$b%02x").mkString)), Signature(Hex("00")))

  private def qc(view: Long, proposalHash: Hash): ProposalQC =
    ProposalQC(view, proposalHash, facHash, NonEmptySet.of(dummyProof("p1")))

  test("acceptVote rejects lower-view votes") {
    val lock = VoteLock(highestVotedView = 5L.some, votedHashAtHighestView = hashA.some, lockedQc = None)
    val result = lock.acceptVote(
      view = 3L,
      proposalHash = hashB,
      effectiveLockedQc = None,
      policy = LegacyViewChangePolicy.FreezeAfterVote
    )
    expect(result.isLeft, s"lower-view vote should be rejected, got: $result")
  }

  test("acceptVote rejects same-view vote with different hash") {
    val lock = VoteLock(highestVotedView = 5L.some, votedHashAtHighestView = hashA.some, lockedQc = None)
    val result = lock.acceptVote(
      view = 5L,
      proposalHash = hashB,
      effectiveLockedQc = None,
      policy = LegacyViewChangePolicy.FreezeAfterVote
    )
    expect(result.isLeft, s"conflicting same-view vote should be rejected, got: $result")
  }

  test("acceptVote accepts same-view vote with same hash (idempotent)") {
    val lock = VoteLock(highestVotedView = 5L.some, votedHashAtHighestView = hashA.some, lockedQc = None)
    val result = lock.acceptVote(
      view = 5L,
      proposalHash = hashA,
      effectiveLockedQc = None,
      policy = LegacyViewChangePolicy.FreezeAfterVote
    )
    expect(result.isRight, s"same-view same-hash vote should be accepted, got: $result")
  }

  test("acceptVote rejects higher-view vote when effectiveLockedQc.hash != proposalHash") {
    val lockedOnHashA = qc(view = 4L, proposalHash = hashA)
    val lock = VoteLock(highestVotedView = 3L.some, votedHashAtHighestView = hashA.some, lockedQc = lockedOnHashA.some)
    val result = lock.acceptVote(
      view = 5L,
      proposalHash = hashB,
      effectiveLockedQc = lockedOnHashA.some,
      policy = LegacyViewChangePolicy.FreezeAfterVote
    )
    expect(result.isLeft, s"voting for different hash than lockedQc should fail, got: $result")
  }

  test("acceptVote accepts higher-view vote when effectiveLockedQc absent") {
    val lock = VoteLock.empty
    val result = lock.acceptVote(
      view = 5L,
      proposalHash = hashB,
      effectiveLockedQc = None,
      policy = LegacyViewChangePolicy.FreezeAfterVote
    )
    expect(result.isRight, s"higher-view vote without lock should succeed, got: $result")
  }

  test("acceptVote rejects a different artifact hash in a higher view after any legacy vote") {
    val lock = VoteLock(highestVotedView = 2L.some, votedHashAtHighestView = hashA.some, lockedQc = None)
    val result = lock.acceptVote(
      view = 3L,
      proposalHash = hashB,
      effectiveLockedQc = None,
      policy = LegacyViewChangePolicy.FreezeAfterVote
    )
    expect(result.left.exists(_.isInstanceOf[VoteRejection.LegacyHigherViewLocked]), s"cross-value vote must fail closed, got: $result")
  }

  test("Currency compatibility policy preserves artifact-only higher-view voting") {
    val lock = VoteLock(highestVotedView = 2L.some, votedHashAtHighestView = hashA.some, lockedQc = None)
    val result = lock.acceptVote(
      view = 3L,
      proposalHash = hashB,
      effectiveLockedQc = None,
      policy = LegacyViewChangePolicy.PreserveLegacy
    )

    expect(result.isRight, s"preserve-legacy policy should retain the existing Currency behavior, got: $result")
  }

  test("acceptVote rejects the same artifact hash in a higher view because the legacy envelope is not certified") {
    val lock = VoteLock(highestVotedView = 2L.some, votedHashAtHighestView = hashA.some, lockedQc = None)
    val result = lock.acceptVote(
      view = 3L,
      proposalHash = hashA,
      effectiveLockedQc = None,
      policy = LegacyViewChangePolicy.FreezeAfterVote
    )
    expect(
      result.left.exists(_.isInstanceOf[VoteRejection.LegacyHigherViewLocked]),
      s"artifact equality cannot certify outcome-envelope equality, got: $result"
    )
  }

  test("an unverified legacy ProposalQC cannot unlock a different higher-view hash") {
    val unverifiedQcForB = qc(view = 2L, proposalHash = hashB)
    val lock = VoteLock(highestVotedView = 2L.some, votedHashAtHighestView = hashA.some, lockedQc = None)
    val result = lock.acceptVote(
      view = 3L,
      proposalHash = hashB,
      effectiveLockedQc = unverifiedQcForB.some,
      policy = LegacyViewChangePolicy.FreezeAfterVote
    )
    expect(
      result.left.exists(_.isInstanceOf[VoteRejection.LegacyHigherViewLocked]),
      s"legacy QC must not unlock cross-value vote, got: $result"
    )
  }

  test("an unverified legacy ProposalQC cannot unlock even a matching artifact in a higher view") {
    val matchingQc = qc(view = 3L, proposalHash = hashC)
    val lock = VoteLock(highestVotedView = 2L.some, votedHashAtHighestView = hashC.some, lockedQc = matchingQc.some)
    val result = lock.acceptVote(
      view = 5L,
      proposalHash = hashC,
      effectiveLockedQc = matchingQc.some,
      policy = LegacyViewChangePolicy.FreezeAfterVote
    )
    expect(
      result.left.exists(_.isInstanceOf[VoteRejection.LegacyHigherViewLocked]),
      s"legacy artifact QC cannot certify the complete outcome envelope, got: $result"
    )
  }

  test("legacy view-change emission and application share the same fail-closed lock predicate") {
    val voted = VoteLock(highestVotedView = 2L.some, votedHashAtHighestView = hashA.some, lockedQc = None)
    val qcOnly = VoteLock.empty.withAdvancedQc(qc(view = 2L, proposalHash = hashA))

    expect(!VoteLock.blocksLegacyViewChange(None, LegacyViewChangePolicy.FreezeAfterVote))
      .and(expect(!VoteLock.blocksLegacyViewChange(VoteLock.empty.some, LegacyViewChangePolicy.FreezeAfterVote)))
      .and(expect(VoteLock.blocksLegacyViewChange(voted.some, LegacyViewChangePolicy.FreezeAfterVote)))
      .and(expect(VoteLock.blocksLegacyViewChange(qcOnly.some, LegacyViewChangePolicy.FreezeAfterVote)))
      .and(expect(!VoteLock.blocksLegacyViewChange(voted.some, LegacyViewChangePolicy.PreserveLegacy)))
  }

  test("withAdvancedQc only advances when newQc.view > existing.view") {
    val existing = qc(view = 4L, proposalHash = hashA)
    val newer = qc(view = 5L, proposalHash = hashB)
    val older = qc(view = 2L, proposalHash = hashC)
    val lock = VoteLock.empty.withAdvancedQc(existing)
    val advanced = lock.withAdvancedQc(newer)
    val notAdvanced = lock.withAdvancedQc(older)
    val sameView = lock.withAdvancedQc(qc(view = 4L, proposalHash = hashB))

    expect(advanced.lockedQc.exists(_.view === 5L), s"should advance to view 5, got: ${advanced.lockedQc}")
      .and(expect(notAdvanced.lockedQc.exists(_.view === 4L), s"should NOT regress to view 2, got: ${notAdvanced.lockedQc}"))
      .and(expect(sameView.lockedQc.exists(_.view === 4L), s"should not advance on equal-view, got: ${sameView.lockedQc}"))
  }
}
