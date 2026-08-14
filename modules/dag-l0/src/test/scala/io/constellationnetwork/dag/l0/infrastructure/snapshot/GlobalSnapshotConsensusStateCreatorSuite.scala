package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.data.NonEmptySet

import scala.concurrent.duration.Duration

import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema.GlobalConsensusKind
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusResources
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.{EvictionCertificate, EvictionReason, EvictionVote}
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import weaver.FunSuite

object GlobalSnapshotConsensusStateCreatorSuite extends FunSuite {

  private def peer(index: Int): PeerId = PeerId(Hex(f"$index%0128x"))
  private val facilitatorsHash = Hash("a" * 64)
  private val parentHash = Hash("b" * 64)

  private def signedVote(voter: PeerId, target: PeerId): Signed[EvictionVote] =
    Signed(
      EvictionVote(target, EvictionReason.Silent, facilitatorsHash, parentHash),
      NonEmptySet.one(SignatureProof(Id(voter.value), Signature(Hex("00"))))
    )

  private def resources(target: PeerId, voter: PeerId): ConsensusResources[GlobalSnapshotArtifact, GlobalConsensusKind] =
    ConsensusResources(
      peerDeclarationsMap = Map.empty,
      acksMap = Map.empty,
      withdrawalsMap = Map.empty,
      ackKinds = Set.empty,
      artifacts = Map.empty,
      updatedAt = Duration.Zero,
      evictionVotes = Map(target -> Map(voter -> signedVote(voter, target)))
    )

  test("abandon retry retransmits the exact stored silent vote for a Core target until ECS assembles") {
    val self = peer(1)
    val coreTarget = peer(2)
    val otherCore = peer(3)
    val stored = resources(coreTarget, self)
    val retransmission = GlobalSnapshotConsensusStateCreator.evictionVoteRetransmission(
      self,
      stored,
      currentCore = Set(self, coreTarget, otherCore),
      currentSigningCommittee = Set(self, coreTarget, otherCore),
      assembled = Set.empty
    )
    val certificate = EvictionCertificate(
      coreTarget,
      EvictionReason.Silent,
      facilitatorsHash,
      parentHash,
      NonEmptySet.one(stored.evictionVotes(coreTarget)(self))
    )
    val afterAssembly = GlobalSnapshotConsensusStateCreator.evictionVoteRetransmission(
      self,
      stored,
      currentCore = Set(self, coreTarget, otherCore),
      currentSigningCommittee = Set(self, coreTarget, otherCore),
      assembled = Set(certificate)
    )

    expect(retransmission.map(_.target).contains(coreTarget)) &&
    expect(retransmission.map(_.vote).contains(stored.evictionVotes(coreTarget)(self))) &&
    expect(retransmission.map(_.recipients).contains(Set(otherCore))) &&
    expect(afterAssembly.isEmpty)
  }

  test("exact activation refuses all-ineligible or singleton committees instead of falling back to local self") {
    val self = peer(1)
    val other = peer(2)

    val empty = GlobalSnapshotConsensusStateCreator.finalizeEligibleCommitteeAtActivation(
      SnapshotOrdinal.unsafeApply(100L),
      certifiedConsensusActivatesAtKey = true,
      eligible = List.empty,
      self,
      quorumThresholdFraction = 2.0 / 3.0
    )
    val singleton = GlobalSnapshotConsensusStateCreator.finalizeEligibleCommitteeAtActivation(
      SnapshotOrdinal.unsafeApply(100L),
      certifiedConsensusActivatesAtKey = true,
      eligible = List(other),
      self,
      quorumThresholdFraction = 2.0 / 3.0
    )
    val viable = GlobalSnapshotConsensusStateCreator.finalizeEligibleCommitteeAtActivation(
      SnapshotOrdinal.unsafeApply(100L),
      certifiedConsensusActivatesAtKey = true,
      eligible = List(self, other),
      self,
      quorumThresholdFraction = 2.0 / 3.0
    )
    val legacyFallback = GlobalSnapshotConsensusStateCreator.finalizeEligibleCommitteeAtActivation(
      SnapshotOrdinal.unsafeApply(99L),
      certifiedConsensusActivatesAtKey = false,
      eligible = List.empty,
      self,
      quorumThresholdFraction = 2.0 / 3.0
    )
    val finalSelectorSingleton = GlobalSnapshotConsensusStateCreator.validateActivationCommittee(
      SnapshotOrdinal.unsafeApply(100L),
      certifiedConsensusActivatesAtKey = true,
      stage = "final selected/signing",
      committee = List(self),
      quorumThresholdFraction = 2.0 / 3.0
    )
    val genesisSingleton = GlobalSnapshotConsensusStateCreator.validateActivationCommittee(
      SnapshotOrdinal.MinValue,
      certifiedConsensusActivatesAtKey = true,
      stage = "genesis",
      committee = List(self),
      quorumThresholdFraction = 1.0
    )
    val unanimityCannotGrow = GlobalSnapshotConsensusStateCreator.validateActivationCommittee(
      SnapshotOrdinal.unsafeApply(100L),
      certifiedConsensusActivatesAtKey = true,
      stage = "unanimity",
      committee = List(self, other),
      quorumThresholdFraction = 1.0
    )

    expect(empty.isLeft) &&
    expect(singleton.isLeft) &&
    expect.same(Right(List(self, other)), viable) &&
    expect.same(Right(List(self)), legacyFallback) &&
    expect(finalSelectorSingleton.left.exists(_.getMessage.contains("final selected/signing committee size=1"))) &&
    expect(genesisSingleton.isRight) &&
    expect(unanimityCannotGrow.left.exists(_.getMessage.contains("next-seat quorum=3")))
  }
}
