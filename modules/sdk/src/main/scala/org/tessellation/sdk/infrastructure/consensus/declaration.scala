package org.tessellation.sdk.infrastructure.consensus

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.infrastructure.consensus.trigger.ConsensusTrigger
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.signature.Signature

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

object declaration {

  sealed trait PeerDeclaration

  @derive(encoder, decoder)
  case class Facility(upperBound: Bound, facilitators: Set[PeerId], trigger: Option[ConsensusTrigger]) extends PeerDeclaration

  @derive(encoder, decoder)
  case class Proposal(hash: Hash) extends PeerDeclaration

  @derive(encoder, decoder)
  case class MajoritySignature(signature: Signature) extends PeerDeclaration

}
