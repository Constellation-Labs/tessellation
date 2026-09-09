package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.syntax.option._

import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema.GlobalConsensusKind._
import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema._
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.{PeerDeclarations, declaration}

trait GlobalSnapshotConsensusOps extends ConsensusOps[GlobalConsensusStep, GlobalConsensusKind]

object GlobalSnapshotConsensusOps {
  def make: GlobalSnapshotConsensusOps = new GlobalSnapshotConsensusOps {
    def collectedKinds(status: GlobalSnapshotStatus): Set[GlobalConsensusKind] =
      status match {
        case _: CollectingFacilities => Set.empty
        case _: CollectingProposals  => Set(Facility)
        case _: CollectingSignatures => Set(Facility, Proposal)
        case _: Finished             => Set(Facility, Proposal, Signature)
      }

    def maybeCollectingKind(status: GlobalSnapshotStatus): Option[GlobalConsensusKind] =
      status match {
        case _: CollectingFacilities => Facility.some
        case _: CollectingProposals  => Proposal.some
        case _: CollectingSignatures => Signature.some
        case _: Finished             => none
      }

    def kindGetter: GlobalConsensusKind => PeerDeclarations => Option[declaration.PeerDeclaration] = {
      case Facility  => _.facility
      case Proposal  => _.proposal
      case Signature => _.signature
    }

    def isFinished(status: GlobalSnapshotStatus): Boolean = status match {
      case _: Finished => true
      case _           => false
    }

    def isProposalPhase(status: GlobalSnapshotStatus): Boolean = status match {
      case _: CollectingProposals => true
      case _                      => false
    }

    def isSignaturesPhase(status: GlobalSnapshotStatus): Boolean = status match {
      case _: CollectingSignatures => true
      case _                       => false
    }

    def phaseIndex(status: GlobalSnapshotStatus): Int = status match {
      case _: CollectingFacilities => 0
      case _: CollectingProposals  => 1
      case _: CollectingSignatures => 2
      case _: Finished             => 3
    }

    def freshCollectingFacilities(status: GlobalSnapshotStatus): Option[GlobalSnapshotStatus] = status match {
      case value: CollectingFacilities => CollectingFacilities(none, value.facilitatorsHash, value.lastSnapshotHash).some
      case value: CollectingProposals  => CollectingFacilities(none, value.facilitatorsHash, value.lastSnapshotHash).some
      case value: CollectingSignatures => CollectingFacilities(none, value.facilitatorsHash, value.lastSnapshotHash).some
      case _: Finished                 => none
    }
  }
}
