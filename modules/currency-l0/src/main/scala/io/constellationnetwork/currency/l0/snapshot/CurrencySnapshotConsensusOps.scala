package io.constellationnetwork.currency.l0.snapshot

import cats.syntax.option._

import io.constellationnetwork.currency.l0.snapshot.schema.CurrencyConsensusKind._
import io.constellationnetwork.currency.l0.snapshot.schema._
import io.constellationnetwork.currency.l0.snapshot.synchronous.{ConsensusOps, PeerDeclarations, declaration}

trait CurrencySnapshotConsensusOps extends ConsensusOps[CurrencySnapshotStatus, CurrencyConsensusKind]

object CurrencySnapshotConsensusOps {

  /** Immutable declaration-domain committee for the current attempt.
    *
    * Intermediate statuses carry the round-start hash even if a voluntary withdrawal or ACK subsequently narrows `state.facilitators`. This
    * prevents asymmetric withdrawal delivery from making honest peers emit declarations in different domains. `Finished` carries the final
    * retained-committee hash used by the next outcome.
    */
  def attemptFacilitatorsHash(status: CurrencySnapshotStatus): io.constellationnetwork.security.hash.Hash =
    status match {
      case CollectingFacilities(_, facilitatorsHash)                      => facilitatorsHash
      case CollectingProposals(_, _, _, _, facilitatorsHash)              => facilitatorsHash
      case CollectingSignatures(_, _, _, _, facilitatorsHash)             => facilitatorsHash
      case CollectingBinarySignatures(_, _, _, _, _, _, facilitatorsHash) => facilitatorsHash
      case Finished(_, _, _, _, _, facilitatorsHash, _)                   => facilitatorsHash
    }

  def make: CurrencySnapshotConsensusOps = new CurrencySnapshotConsensusOps {
    override def collectedKinds(status: CurrencySnapshotStatus): Set[CurrencyConsensusKind] =
      status match {
        case CollectingFacilities(_, _)                      => Set.empty
        case CollectingProposals(_, _, _, _, _)              => Set(Facility)
        case CollectingSignatures(_, _, _, _, _)             => Set(Facility, Proposal)
        case CollectingBinarySignatures(_, _, _, _, _, _, _) => Set(Facility, Proposal, Signature)
        case Finished(_, _, _, _, _, _, _)                   => Set(Facility, Proposal, Signature, BinarySignature)
      }

    override def maybeCollectingKind(status: CurrencySnapshotStatus): Option[CurrencyConsensusKind] =
      status match {
        case CollectingFacilities(_, _)                      => Facility.some
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
  }
}
