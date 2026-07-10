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
        case CollectingFacilities(_, _, _)            => Set.empty
        case CollectingProposals(_, _, _, _, _, _, _) => Set(Facility)
        case CollectingSignatures(_, _, _, _, _)      => Set(Facility, Proposal)
        case Finished(_, _, _, _, _, _)               => Set(Facility, Proposal, Signature)
      }

    def maybeCollectingKind(status: GlobalSnapshotStatus): Option[GlobalConsensusKind] =
      status match {
        case CollectingFacilities(_, _, _)            => Facility.some
        case CollectingProposals(_, _, _, _, _, _, _) => Proposal.some
        case CollectingSignatures(_, _, _, _, _)      => Signature.some
        case Finished(_, _, _, _, _, _)               => none
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
      case CollectingFacilities(_, facHash, lastSnap)            => CollectingFacilities(none, facHash, lastSnap).some
      case CollectingProposals(_, _, _, facHash, lastSnap, _, _) => CollectingFacilities(none, facHash, lastSnap).some
      case CollectingSignatures(_, _, _, facHash, lastSnap)      => CollectingFacilities(none, facHash, lastSnap).some
      case _: Finished                                           => none
    }
  }
}
