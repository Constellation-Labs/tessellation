package io.constellationnetwork.currency.l0.snapshot

import cats.Show
import cats.syntax.show._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.schema.currency.CurrencySnapshotContext
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotArtifact
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{ConsensusOperationalState, PerPeerOperationalRecord, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.statechannel.StateChannelSnapshotBinary

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import monocle.Lens
import monocle.macros.GenLens

object schema {

  @derive(eqv)
  sealed trait CurrencyConsensusStep

  object CurrencyConsensusStep {
    implicit val show: Show[CurrencyConsensusStep] = Show.show {
      case CollectingFacilities(maybeTrigger, facilitatorsHash, lastSnapshotHash) =>
        s"CollectingFacilities{maybeTrigger=${maybeTrigger.show}, facilitatorsHash=${facilitatorsHash.show}, lastSnapshotHash=${lastSnapshotHash.show}}"
      case CollectingProposals(majorityTrigger, proposalArtifactInfo, candidates, facilitatorsHash, lastSnapshotHash, observedResponders) =>
        s"CollectingProposals{majorityTrigger=${majorityTrigger.show}, proposalArtifactInfo=${proposalArtifactInfo.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}, lastSnapshotHash=${lastSnapshotHash.show}, observedRespondersCount=${observedResponders.size}}"
      case CollectingSignatures(majorityArtifactInfo, majorityTrigger, candidates, facilitatorsHash, lastSnapshotHash) =>
        s"CollectingSignatures{majorityArtifactInfo=${majorityArtifactInfo.show}, ${majorityTrigger.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}, lastSnapshotHash=${lastSnapshotHash.show}}"
      case CollectingBinarySignatures(_, _, _, majorityTrigger, candidates, facilitatorsHash, lastSnapshotHash) =>
        s"CollectingBinarySignatures{majorityTrigger=${majorityTrigger.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}, lastSnapshotHash=${lastSnapshotHash.show}}"
      case Finished(_, binaryArtifactHash, _, majorityTrigger, candidates, facilitatorsHash, snapshotHash) =>
        s"Finished{binaryArtifactHash=${binaryArtifactHash}, majorityTrigger=${majorityTrigger.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}, snapshotHash=${snapshotHash.show}}"
    }
  }

  final case class CollectingFacilities(
    maybeTrigger: Option[ConsensusTrigger],
    facilitatorsHash: Hash,
    lastSnapshotHash: Hash
  ) extends CurrencyConsensusStep

  final case class CollectingProposals(
    majorityTrigger: ConsensusTrigger,
    proposalArtifactInfo: ArtifactInfo[CurrencySnapshotArtifact, CurrencySnapshotContext],
    candidates: Candidates,
    facilitatorsHash: Hash,
    lastSnapshotHash: Hash,
    // v7 (codex turn 2 fix #2): leader's observedResponders frozen at proposal-build time —
    // see dag-l0 mirror for full rationale. Re-spread reads from this immutable status field
    // instead of recomputing from current resources.
    observedResponders: List[PeerId]
  ) extends CurrencyConsensusStep

  final case class CollectingSignatures(
    majorityArtifactInfo: ArtifactInfo[CurrencySnapshotArtifact, CurrencySnapshotContext],
    majorityTrigger: ConsensusTrigger,
    candidates: Candidates,
    facilitatorsHash: Hash,
    lastSnapshotHash: Hash
  ) extends CurrencyConsensusStep

  final case class CollectingBinarySignatures(
    signedMajorityArtifact: Signed[CurrencySnapshotArtifact],
    context: CurrencySnapshotContext,
    binary: StateChannelSnapshotBinary,
    majorityTrigger: ConsensusTrigger,
    candidates: Candidates,
    facilitatorsHash: Hash,
    lastSnapshotHash: Hash
  ) extends CurrencyConsensusStep

  @derive(encoder, decoder, eqv)
  final case class Finished(
    signedMajorityArtifact: Signed[CurrencySnapshotArtifact],
    binaryArtifactHash: Hash,
    context: CurrencySnapshotContext,
    majorityTrigger: ConsensusTrigger,
    candidates: Candidates,
    facilitatorsHash: Hash,
    snapshotHash: Hash
  ) extends CurrencyConsensusStep

  @derive(encoder, decoder, eqv, show)
  sealed trait CurrencyConsensusKind
  object CurrencyConsensusKind {
    case object Facility extends CurrencyConsensusKind

    case object Proposal extends CurrencyConsensusKind

    case object Signature extends CurrencyConsensusKind

    case object BinarySignature extends CurrencyConsensusKind
  }

  @derive(encoder, decoder, eqv)
  final case class CurrencyConsensusOutcome(
    key: CurrencySnapshotKey,
    facilitators: Facilitators,
    removedFacilitators: RemovedFacilitators,
    withdrawnFacilitators: WithdrawnFacilitators,
    eligibleFacilitators: EligibleFacilitators,
    finished: Finished,
    removalPenalties: SortedMap[PeerId, Int] = SortedMap.empty,
    deferralCountdown: SortedMap[PeerId, Int] = SortedMap.empty,
    peerQuality: SortedMap[PeerId, (Int, Int)] = SortedMap.empty,
    // Lifetime count of times this peer was evicted. Scales removalPenaltyRounds
    // exponentially so repeat offenders get progressively longer bans.
    cumulativeMissCounts: SortedMap[PeerId, Long] = SortedMap.empty,
    // Sliding window of (ordinal -> proofs.size) for the last ~10 ordinals, used for
    // bootstrap warmup classification. See dag-l0 mirror for full rationale.
    recentProofSizes: SortedMap[SnapshotOrdinal, Int] = SortedMap.empty,
    // B2 re-admission gate: peers whose `removalPenalty` expired enter this map
    // at `readmissionProbationRounds` and count down one per finished round. See
    // dag-l0 mirror for full rationale.
    readmissionCountdown: SortedMap[PeerId, Int] = SortedMap.empty
  ) {
    def eligibleOrFacilitators: List[PeerId] =
      if (eligibleFacilitators.value.nonEmpty) eligibleFacilitators.value
      else facilitators.value

    // v20+v21 mirror of GlobalConsensusOutcome.toOperationalState. See dag-l0 schema.
    def toOperationalState: ConsensusOperationalState = {
      val keys: Set[PeerId] =
        (peerQuality.keysIterator ++
          removalPenalties.keysIterator ++
          cumulativeMissCounts.keysIterator ++
          readmissionCountdown.keysIterator ++
          deferralCountdown.keysIterator).toSet
      val perPeer: SortedMap[PeerId, PerPeerOperationalRecord] =
        SortedMap.from(
          keys.iterator.map { pid =>
            pid -> PerPeerOperationalRecord(
              quality = peerQuality.getOrElse(pid, (0, 0)),
              removalPenalty = removalPenalties.getOrElse(pid, 0),
              cumulativeMissCount = cumulativeMissCounts.getOrElse(pid, 0L),
              readmissionCountdown = readmissionCountdown.getOrElse(pid, 0),
              deferralCountdown = deferralCountdown.getOrElse(pid, 0)
            )
          }
        )
      ConsensusOperationalState(perPeer = perPeer, recentProofSizes = recentProofSizes)
    }
  }

  object CurrencyConsensusOutcome {
    implicit val _artifact: Lens[CurrencyConsensusOutcome, Signed[CurrencySnapshotArtifact]] =
      GenLens[CurrencyConsensusOutcome](_.finished.signedMajorityArtifact)
    implicit val _context: Lens[CurrencyConsensusOutcome, CurrencySnapshotContext] =
      GenLens[CurrencyConsensusOutcome](_.finished.context)
    implicit val _facilitators: Lens[CurrencyConsensusOutcome, Facilitators] =
      GenLens[CurrencyConsensusOutcome](_.facilitators)
    implicit val _key: Lens[CurrencyConsensusOutcome, CurrencySnapshotKey] =
      GenLens[CurrencyConsensusOutcome](_.key)
    implicit val _trigger: Lens[CurrencyConsensusOutcome, ConsensusTrigger] =
      GenLens[CurrencyConsensusOutcome](_.finished.majorityTrigger)
  }
}
