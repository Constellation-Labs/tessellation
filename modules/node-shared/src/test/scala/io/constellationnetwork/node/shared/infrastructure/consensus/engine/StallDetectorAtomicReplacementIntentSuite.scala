package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.data.NonEmptySet

import io.constellationnetwork.node.shared.infrastructure.consensus.FinalityParticipationAuditor
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.{EvictionReason, EvictionVote}
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import weaver.FunSuite

object StallDetectorAtomicReplacementIntentSuite extends FunSuite {

  private def peer(index: Int): PeerId = PeerId(Hex(f"$index%0128x"))

  private val self = peer(1)
  private val committee = Set(self, peer(2), peer(3), peer(4))
  private val parentCommittee = committee
  private val parentHash = Hash.fromBytes("parent".getBytes("UTF-8"))
  private val facilitatorsHash = Hash.fromBytes("frozen-facilitators".getBytes("UTF-8"))
  private val target = FinalityParticipationAuditor.selectTarget(committee, parentCommittee, parentHash).get
  private val exactVote = EvictionVote(target, EvictionReason.Silent, facilitatorsHash, parentHash)

  private def signed(voter: PeerId, vote: EvictionVote): Signed[EvictionVote] =
    Signed(vote, NonEmptySet.one(SignatureProof(Id(voter.value), Signature(Hex("00")))))

  private def select(
    votes: Map[PeerId, Signed[EvictionVote]] = Map(target -> signed(self, exactVote)),
    currentCommittee: Set[PeerId] = committee,
    parentRoundCommittee: Set[PeerId] = parentCommittee,
    expectedFacilitatorsHash: Hash = facilitatorsHash,
    expectedParentHash: Hash = parentHash,
    selfIsCore: Boolean = true,
    enabled: Boolean = true,
    cadenceAllowed: Boolean = true,
    maxTargets: Int = 1
  ): List[PeerId] =
    StallDetector.atomicReplacementIntentTargets(
      selfId = self,
      selfIsCore = selfIsCore,
      atomicReplacementEnabled = enabled,
      cadenceAllowed = cadenceAllowed,
      currentCommittee = currentCommittee,
      parentRoundCommittee = parentRoundCommittee,
      selfEvictionVotes = votes,
      expectedFacilitatorsHash = expectedFacilitatorsHash,
      expectedParentHash = expectedParentHash,
      entropy = parentHash,
      maxTargets = maxTargets
    )

  test("an exact stored self vote authorizes only the deterministic auditor target") {
    expect.same(List(target), select())
  }

  test("a generic self eviction vote for another peer cannot authorize the atomic lane") {
    val other = (committee - target).head
    val otherVote = EvictionVote(other, EvictionReason.Silent, facilitatorsHash, parentHash)

    expect.same(List.empty[PeerId], select(Map(other -> signed(self, otherVote))))
  }

  test("stale facilitator hash cannot authorize the atomic lane") {
    val stale = Hash.fromBytes("stale-facilitators".getBytes("UTF-8"))
    val vote = exactVote.copy(facilitatorsHash = stale)
    expect.same(List.empty[PeerId], select(votes = Map(target -> signed(self, vote))))
  }

  test("stale parent hash cannot authorize the atomic lane") {
    val stale = Hash.fromBytes("stale-parent".getBytes("UTF-8"))
    val vote = exactVote.copy(lastSnapshotHash = stale)
    expect.same(List.empty[PeerId], select(votes = Map(target -> signed(self, vote))))
  }

  test("a resource keyed as self but signed by another peer cannot authorize the atomic lane") {
    val otherSigner = (committee - self).head
    expect.same(List.empty[PeerId], select(votes = Map(target -> signed(otherSigner, exactVote))))
  }

  test("a self-target eviction intent cannot authorize its paired admission lane") {
    val selfOnlyTarget = FinalityParticipationAuditor.selectTarget(Set(self), Set(self), parentHash).get
    val selfVote = EvictionVote(self, EvictionReason.Silent, facilitatorsHash, parentHash)

    expect.same(
      List.empty[PeerId],
      select(
        votes = Map(self -> signed(self, selfVote)),
        currentCommittee = Set(self),
        parentRoundCommittee = Set(self)
      )
    ) && expect.same(self, selfOnlyTarget)
  }

  test("non-Core, disabled, off-cadence, and zero-budget voters fail closed") {
    expect(select(selfIsCore = false).isEmpty) &&
    expect(select(enabled = false).isEmpty) &&
    expect(select(cadenceAllowed = false).isEmpty) &&
    expect(select(maxTargets = 0).isEmpty)
  }

  test("replacement target and probation nominee cannot enter the paired open lane") {
    val candidate = peer(5)
    val candidates = StallDetector.excludeAtomicReplacementTargets(Set(target, candidate), List(target))
    val selected = StallDetector.openAdmissionTargets(
      candidates = candidates,
      committee = committee,
      probation = Set(candidate),
      alreadyVotedBySelf = Set.empty,
      entropy = parentHash,
      maxOpenAdmissions = 1,
      selfIsCore = true
    )

    expect(!candidates.contains(target)) && expect(selected.isEmpty)
  }
}
