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
        case _: CollectingFacilities       => Set.empty
        case _: CollectingProposals        => Set(Facility)
        case _: CollectingSignatures       => Set(Facility, Proposal)
        case _: CollectingBinarySignatures => Set(Facility, Proposal, Signature)
        case _: Finished                   => Set(Facility, Proposal, Signature, BinarySignature)
      }

    override def maybeCollectingKind(status: CurrencySnapshotStatus): Option[CurrencyConsensusKind] =
      status match {
        case _: CollectingFacilities       => Facility.some
        case _: CollectingProposals        => Proposal.some
        case _: CollectingSignatures       => Signature.some
        case _: CollectingBinarySignatures => BinarySignature.some
        case _: Finished                   => none
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

    // Currency-l0 has both MajoritySignature (CollectingSignatures) and BinarySignature
    // (CollectingBinarySignatures) phases where the round waits on peer signatures before
    // finalization. Both need StallDetector heartbeat pumping for the same reason as dag-l0
    // (quorum-but-not-full grace paths return none without self-re-trigger).
    override def isSignaturesPhase(status: CurrencySnapshotStatus): Boolean = status match {
      case _: CollectingSignatures       => true
      case _: CollectingBinarySignatures => true
      case _                             => false
    }

    override def phaseIndex(status: CurrencySnapshotStatus): Int = status match {
      case _: CollectingFacilities       => 0
      case _: CollectingProposals        => 1
      case _: CollectingSignatures       => 2
      case _: CollectingBinarySignatures => 3
      case _: Finished                   => 4
    }

    override def freshCollectingFacilities(status: CurrencySnapshotStatus): Option[CurrencySnapshotStatus] = status match {
      case value: CollectingFacilities => CollectingFacilities(none, value.facilitatorsHash, value.lastSnapshotHash).some
      case value: CollectingProposals  => CollectingFacilities(none, value.facilitatorsHash, value.lastSnapshotHash).some
      case value: CollectingSignatures => CollectingFacilities(none, value.facilitatorsHash, value.lastSnapshotHash).some
      case value: CollectingBinarySignatures =>
        CollectingFacilities(none, value.facilitatorsHash, value.lastSnapshotHash).some
      case _: Finished => none
    }
  }
}
