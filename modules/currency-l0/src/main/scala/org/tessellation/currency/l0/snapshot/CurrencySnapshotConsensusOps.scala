package org.tessellation.currency.l0.snapshot

import cats.syntax.option._

import org.tessellation.currency.l0.snapshot.schema.CurrencyConsensusKind._
import org.tessellation.currency.l0.snapshot.schema._
import org.tessellation.node.shared.infrastructure.consensus.{ConsensusOps, PeerDeclarations, declaration}

trait CurrencySnapshotConsensusOps extends ConsensusOps[CurrencySnapshotStatus, CurrencyConsensusKind]

object CurrencySnapshotConsensusOps {
  def make: CurrencySnapshotConsensusOps = new CurrencySnapshotConsensusOps {
    override def collectedKinds(status: CurrencySnapshotStatus): Set[CurrencyConsensusKind] =
      status match {
        case CollectingFacilities(_, _)                   => Set.empty
        case CollectingProposals(_, _, _, _)              => Set(Facility)
        case CollectingSignatures(_, _, _, _)             => Set(Facility, Proposal)
        case CollectingBinarySignatures(_, _, _, _, _, _) => Set(Facility, Proposal, Signature)
        case Finished(_, _, _, _, _, _)                   => Set(Facility, Proposal, Signature, BinarySignature)
      }

    override def maybeCollectingKind(status: CurrencySnapshotStatus): Option[CurrencyConsensusKind] =
      status match {
        case CollectingFacilities(_, _)                   => Facility.some
        case CollectingProposals(_, _, _, _)              => Proposal.some
        case CollectingSignatures(_, _, _, _)             => Signature.some
        case CollectingBinarySignatures(_, _, _, _, _, _) => BinarySignature.some
        case Finished(_, _, _, _, _, _)                   => none
      }

    override def kindGetter: CurrencyConsensusKind => PeerDeclarations => Option[declaration.PeerDeclaration] = {
      case Facility        => _.facility
      case Proposal        => _.proposal
      case Signature       => _.signature
      case BinarySignature => _.binarySignature
    }
  }
}
