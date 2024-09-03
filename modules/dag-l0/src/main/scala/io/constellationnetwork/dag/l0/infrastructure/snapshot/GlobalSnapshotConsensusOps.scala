package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.syntax.option._

import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema.GlobalConsensusKind._
import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema._
import io.constellationnetwork.node.shared.infrastructure.consensus.{ConsensusOps, PeerDeclarations, declaration}

trait GlobalSnapshotConsensusOps extends ConsensusOps[GlobalConsensusStep, GlobalConsensusKind]

object GlobalSnapshotConsensusOps {
  def make: GlobalSnapshotConsensusOps = new GlobalSnapshotConsensusOps {
    def collectedKinds(status: GlobalSnapshotStatus): Set[GlobalConsensusKind] =
      status match {
        case CollectingFacilities(_, _)       => Set.empty
        case CollectingProposals(_, _, _, _)  => Set(Facility)
        case CollectingSignatures(_, _, _, _) => Set(Facility, Proposal)
        case Finished(_, _, _, _, _)          => Set(Facility, Proposal, Signature)
      }

    def maybeCollectingKind(status: GlobalSnapshotStatus): Option[GlobalConsensusKind] =
      status match {
        case CollectingFacilities(_, _)       => Facility.some
        case CollectingProposals(_, _, _, _)  => Proposal.some
        case CollectingSignatures(_, _, _, _) => Signature.some
        case Finished(_, _, _, _, _)          => none
      }

    def kindGetter: GlobalConsensusKind => PeerDeclarations => Option[declaration.PeerDeclaration] = {
      case Facility  => _.facility
      case Proposal  => _.proposal
      case Signature => _.signature
    }
  }
}
