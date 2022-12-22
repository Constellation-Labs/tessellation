package org.tessellation.sdk.infrastructure.consensus

import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.security.hash.Hash
import org.tessellation.schema.security.signature.signature.Signature
import org.tessellation.sdk.infrastructure.consensus.trigger.ConsensusTrigger

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

object declaration {

  sealed trait PeerDeclaration {
    def facilitatorsHash: Hash
  }

  @derive(eqv, show, encoder, decoder)
  case class Facility(upperBound: Bound, facilitators: Set[PeerId], trigger: Option[ConsensusTrigger], facilitatorsHash: Hash)
      extends PeerDeclaration

  @derive(eqv, show, encoder, decoder)
  case class Proposal(hash: Hash, facilitatorsHash: Hash) extends PeerDeclaration

  @derive(eqv, show, encoder, decoder)
  case class MajoritySignature(signature: Signature, facilitatorsHash: Hash) extends PeerDeclaration

  object kind {

    @derive(eqv, show, encoder, decoder)
    sealed trait PeerDeclarationKind

    @derive(eqv, show, encoder, decoder)
    case object Facility extends PeerDeclarationKind

    @derive(eqv, show, encoder, decoder)
    case object Proposal extends PeerDeclarationKind

    @derive(eqv, show, encoder, decoder)
    case object MajoritySignature extends PeerDeclarationKind

  }

}
