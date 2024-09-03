package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.Show
import cats.syntax.show._

import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import monocle.Lens
import monocle.macros.GenLens

object schema {

  @derive(eqv)
  sealed trait GlobalConsensusStep

  object GlobalConsensusStep {
    implicit val show: Show[GlobalConsensusStep] = Show.show {
      case CollectingFacilities(maybeTrigger, facilitatorsHash) =>
        s"CollectingFacilities{maybeTrigger=${maybeTrigger.show}, facilitatorsHash=${facilitatorsHash.show}}"
      case CollectingProposals(majorityTrigger, proposalArtifactInfo, candidates, facilitatorsHash) =>
        s"CollectingProposals{majorityTrigger=${majorityTrigger.show}, proposalArtifactInfo=${proposalArtifactInfo.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}}"
      case CollectingSignatures(majorityArtifactInfo, majorityTrigger, candidates, facilitatorsHash) =>
        s"CollectingSignatures{majorityArtifactInfo=${majorityArtifactInfo.show}, ${majorityTrigger.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}}"
      case Finished(_, _, majorityTrigger, candidates, facilitatorsHash) =>
        s"Finished{majorityTrigger=${majorityTrigger.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}}"
    }
  }

  final case class CollectingFacilities(
    maybeTrigger: Option[ConsensusTrigger],
    facilitatorsHash: Hash
  ) extends GlobalConsensusStep

  final case class CollectingProposals(
    majorityTrigger: ConsensusTrigger,
    proposalArtifactInfo: ArtifactInfo[GlobalSnapshotArtifact, GlobalSnapshotContext],
    candidates: Candidates,
    facilitatorsHash: Hash
  ) extends GlobalConsensusStep

  final case class CollectingSignatures(
    majorityArtifactInfo: ArtifactInfo[GlobalSnapshotArtifact, GlobalSnapshotContext],
    majorityTrigger: ConsensusTrigger,
    candidates: Candidates,
    facilitatorsHash: Hash
  ) extends GlobalConsensusStep

  @derive(encoder, decoder, eqv)
  final case class Finished(
    signedMajorityArtifact: Signed[GlobalSnapshotArtifact],
    context: GlobalSnapshotContext,
    majorityTrigger: ConsensusTrigger,
    candidates: Candidates,
    facilitatorsHash: Hash
  ) extends GlobalConsensusStep

  @derive(encoder, decoder, eqv)
  final case class GlobalConsensusOutcome(
    key: GlobalSnapshotKey,
    facilitators: Facilitators,
    removedFacilitators: RemovedFacilitators,
    withdrawnFacilitators: WithdrawnFacilitators,
    finished: Finished
  )

  @derive(encoder, decoder, eqv, show)
  sealed trait GlobalConsensusKind

  object GlobalConsensusKind {
    case object Facility extends GlobalConsensusKind

    case object Proposal extends GlobalConsensusKind

    case object Signature extends GlobalConsensusKind
  }

  object GlobalConsensusOutcome {
    implicit val _artifact: Lens[GlobalConsensusOutcome, Signed[GlobalSnapshotArtifact]] =
      GenLens[GlobalConsensusOutcome](_.finished.signedMajorityArtifact)
    implicit val _context: Lens[GlobalConsensusOutcome, GlobalSnapshotContext] =
      GenLens[GlobalConsensusOutcome](_.finished.context)
    implicit val _facilitators: Lens[GlobalConsensusOutcome, Facilitators] =
      GenLens[GlobalConsensusOutcome](_.facilitators)
    implicit val _key: Lens[GlobalConsensusOutcome, GlobalSnapshotKey] =
      GenLens[GlobalConsensusOutcome](_.key)
    implicit val _trigger: Lens[GlobalConsensusOutcome, ConsensusTrigger] =
      GenLens[GlobalConsensusOutcome](_.finished.majorityTrigger)
  }
}
