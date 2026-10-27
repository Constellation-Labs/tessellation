package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.data.NonEmptySet

import scala.concurrent.duration._

import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema.GlobalConsensusKind
import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, EventCutterConfig}
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusResources
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.{EvictionCertificate, EvictionReason, EvictionVote}
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt
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

  test("a key below certified activation is refused for production; keys at or above it proceed") {
    val config = ConsensusConfig(
      timeTriggerInterval = 10.seconds,
      declarationTimeout = 10.seconds,
      declarationRangeLimit = 100L,
      lockDuration = 10.seconds,
      eventCutter = EventCutterConfig(PosInt(1024), PosInt(1024)),
      certifiedConsensusActivationKey = 100L
    )
    def attempt(key: Long): Either[Throwable, Unit] =
      GlobalSnapshotConsensusStateCreator.requireCertifiedProduction[Either[Throwable, *]](config, SnapshotOrdinal.unsafeApply(key))

    expect(attempt(99L).left.exists(_.isInstanceOf[GlobalSnapshotConsensusStateCreator.CertifiedConsensusNotActiveForProduction])) &&
    expect(attempt(100L).isRight) &&
    expect(attempt(101L).isRight) &&
    expect(
      GlobalSnapshotConsensusStateCreator
        .requireCertifiedProduction[Either[Throwable, *]](
          config.copy(certifiedConsensusActivationKey = Long.MaxValue),
          SnapshotOrdinal.unsafeApply(5L)
        )
        .isLeft
    )
  }
}
