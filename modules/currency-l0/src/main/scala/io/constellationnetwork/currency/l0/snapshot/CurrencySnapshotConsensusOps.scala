package io.constellationnetwork.currency.l0.snapshot

import cats.syntax.option._

import io.constellationnetwork.currency.l0.snapshot.schema.CurrencyConsensusKind._
import io.constellationnetwork.currency.l0.snapshot.schema._
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.{PeerDeclarations, declaration}

trait CurrencySnapshotConsensusOps extends ConsensusOps[CurrencySnapshotStatus, CurrencyConsensusKind]

object CurrencySnapshotConsensusOps {
  def make: CurrencySnapshotConsensusOps = new CurrencySnapshotConsensusOps {
    override def collectedKinds(status: CurrencySnapshotStatus): Set[CurrencyConsensusKind] =
      status match {
        case CollectingFacilities(_, _, _)                   => Set.empty
        case CollectingProposals(_, _, _, _, _)              => Set(Facility)
        case CollectingSignatures(_, _, _, _, _)             => Set(Facility, Proposal)
        case CollectingBinarySignatures(_, _, _, _, _, _, _) => Set(Facility, Proposal, Signature)
        case Finished(_, _, _, _, _, _, _)                   => Set(Facility, Proposal, Signature, BinarySignature)
      }

    override def maybeCollectingKind(status: CurrencySnapshotStatus): Option[CurrencyConsensusKind] =
      status match {
        case CollectingFacilities(_, _, _)                   => Facility.some
        case CollectingProposals(_, _, _, _, _)              => Proposal.some
        case CollectingSignatures(_, _, _, _, _)             => Signature.some
        case CollectingBinarySignatures(_, _, _, _, _, _, _) => BinarySignature.some
        case Finished(_, _, _, _, _, _, _)                   => none
      }

    override def kindGetter: CurrencyConsensusKind => PeerDeclarations => Option[declaration.PeerDeclaration] = {
      case Facility        => _.facility
      case Proposal        => _.proposal
      case Signature       => _.signature
      case BinarySignature => _.binarySignature
    }

    override def isFinished(status: CurrencySnapshotStatus): Boolean = status match {
      case _: Finished => true
      case _           => false
    }

    override def isProposalPhase(status: CurrencySnapshotStatus): Boolean = status match {
      case _: CollectingProposals => true
      case _                      => false
    }

    override def phaseIndex(status: CurrencySnapshotStatus): Int = status match {
      case _: CollectingFacilities       => 0
      case _: CollectingProposals        => 1
      case _: CollectingSignatures       => 2
      case _: CollectingBinarySignatures => 3
      case _: Finished                   => 4
    }
  }
}
