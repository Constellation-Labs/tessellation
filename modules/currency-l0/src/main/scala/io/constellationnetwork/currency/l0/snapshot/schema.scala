package io.constellationnetwork.currency.l0.snapshot

import cats.syntax.all._
import cats.{Eq, Show}

import scala.collection.immutable.SortedSet

import io.constellationnetwork.currency.l0.snapshot.synchronous._
import io.constellationnetwork.currency.schema.currency.CurrencySnapshotContext
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotArtifact
import io.constellationnetwork.schema.peer.PeerId
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
      case CollectingFacilities(maybeTrigger, facilitatorsHash) =>
        s"CollectingFacilities{maybeTrigger=${maybeTrigger.show}, facilitatorsHash=${facilitatorsHash.show}}"
      case CollectingProposals(majorityTrigger, proposalArtifactInfo, candidates, _, facilitatorsHash) =>
        s"CollectingProposals{majorityTrigger=${majorityTrigger.show}, proposalArtifactInfo=${proposalArtifactInfo.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}}"
      case CollectingSignatures(majorityArtifactInfo, majorityTrigger, candidates, _, facilitatorsHash) =>
        s"CollectingSignatures{majorityArtifactInfo=${majorityArtifactInfo.show}, ${majorityTrigger.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}}"
      case CollectingBinarySignatures(_, _, _, majorityTrigger, candidates, _, facilitatorsHash) =>
        s"CollectingBinarySignatures{majorityTrigger=${majorityTrigger.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}}"
      case Finished(_, binaryArtifactHash, _, majorityTrigger, candidates, facilitatorsHash, candidateCursor) =>
        s"Finished{binaryArtifactHash=${binaryArtifactHash}, majorityTrigger=${majorityTrigger.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}, candidateCursor=${candidateCursor.show}}"
    }
  }

  final case class CollectingFacilities(
    maybeTrigger: Option[ConsensusTrigger],
    facilitatorsHash: Hash
  ) extends CurrencyConsensusStep

  final case class CollectingProposals(
    majorityTrigger: ConsensusTrigger,
    proposalArtifactInfo: ArtifactInfo[CurrencySnapshotArtifact, CurrencySnapshotContext],
    candidates: Candidates,
    ownAcceptedEventHashes: SortedSet[Hash],
    facilitatorsHash: Hash
  ) extends CurrencyConsensusStep

  final case class CollectingSignatures(
    majorityArtifactInfo: ArtifactInfo[CurrencySnapshotArtifact, CurrencySnapshotContext],
    majorityTrigger: ConsensusTrigger,
    candidates: Candidates,
    acceptedEventHashes: SortedSet[Hash],
    facilitatorsHash: Hash
  ) extends CurrencyConsensusStep

  final case class CollectingBinarySignatures(
    signedMajorityArtifact: Signed[CurrencySnapshotArtifact],
    context: CurrencySnapshotContext,
    binary: StateChannelSnapshotBinary,
    majorityTrigger: ConsensusTrigger,
    candidates: Candidates,
    acceptedEventHashes: SortedSet[Hash],
    facilitatorsHash: Hash
  ) extends CurrencyConsensusStep

  @derive(encoder, decoder)
  final case class Finished(
    signedMajorityArtifact: Signed[CurrencySnapshotArtifact],
    binaryArtifactHash: Hash,
    context: CurrencySnapshotContext,
    majorityTrigger: ConsensusTrigger,
    candidates: Candidates,
    facilitatorsHash: Hash,
    candidateCursor: Option[PeerId]
  ) extends CurrencyConsensusStep

  object Finished {

    /** Public Currency binary content embeds the complete signed artifact, so randomized proof bytes are part of the exact restart identity
      * even though repository-wide `Signed[A]` equality is value-oriented.
      */
    implicit val eqInstance: Eq[Finished] = Eq.instance { (left, right) =>
      left.signedMajorityArtifact.value === right.signedMajorityArtifact.value &&
      left.signedMajorityArtifact.proofs === right.signedMajorityArtifact.proofs &&
      left.binaryArtifactHash === right.binaryArtifactHash &&
      left.context === right.context &&
      left.majorityTrigger === right.majorityTrigger &&
      left.candidates === right.candidates &&
      left.facilitatorsHash === right.facilitatorsHash &&
      left.candidateCursor === right.candidateCursor
    }
  }

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
    finished: Finished
  )

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
