package org.tessellation.currency.l0.snapshot

import cats.Show
import cats.syntax.show._

import org.tessellation.currency.schema.currency.CurrencySnapshotContext
import org.tessellation.node.shared.infrastructure.consensus._
import org.tessellation.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import org.tessellation.node.shared.snapshot.currency.CurrencySnapshotArtifact
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.StateChannelSnapshotBinary

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
      case CollectingProposals(majorityTrigger, proposalArtifactInfo, candidates, facilitatorsHash) =>
        s"CollectingProposals{majorityTrigger=${majorityTrigger.show}, proposalArtifactInfo=${proposalArtifactInfo.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}}"
      case CollectingSignatures(majorityArtifactInfo, majorityTrigger, candidates, facilitatorsHash) =>
        s"CollectingSignatures{majorityArtifactInfo=${majorityArtifactInfo.show}, ${majorityTrigger.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}}"
      case CollectingBinarySignatures(_, _, _, majorityTrigger, candidates, facilitatorsHash) =>
        s"CollectingBinarySignatures{majorityTrigger=${majorityTrigger.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}}"
      case Finished(_, binaryArtifactHash, _, majorityTrigger, candidates, facilitatorsHash) =>
        s"Finished{binaryArtifactHash=${binaryArtifactHash}, majorityTrigger=${majorityTrigger.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}}"
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
    facilitatorsHash: Hash
  ) extends CurrencyConsensusStep

  final case class CollectingSignatures(
    majorityArtifactInfo: ArtifactInfo[CurrencySnapshotArtifact, CurrencySnapshotContext],
    majorityTrigger: ConsensusTrigger,
    candidates: Candidates,
    facilitatorsHash: Hash
  ) extends CurrencyConsensusStep

  final case class CollectingBinarySignatures(
    signedMajorityArtifact: Signed[CurrencySnapshotArtifact],
    context: CurrencySnapshotContext,
    binary: StateChannelSnapshotBinary,
    majorityTrigger: ConsensusTrigger,
    candidates: Candidates,
    facilitatorsHash: Hash
  ) extends CurrencyConsensusStep

  @derive(encoder, decoder, eqv)
  final case class Finished(
    signedMajorityArtifact: Signed[CurrencySnapshotArtifact],
    binaryArtifactHash: Hash,
    context: CurrencySnapshotContext,
    majorityTrigger: ConsensusTrigger,
    candidates: Candidates,
    facilitatorsHash: Hash
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
