package org.tessellation.sdk.infrastructure.consensus

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.infrastructure.consensus.trigger.ConsensusTrigger
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.signature.Signature

import derevo.cats.{eqv, show}
import derevo.derive

object declaration {

  @derive(eqv, show)
  sealed trait PeerDeclaration

  @derive(eqv, show)
  case class Facility(upperBound: Bound, facilitators: Set[PeerId], trigger: Option[ConsensusTrigger])
      extends PeerDeclaration

  @derive(eqv, show)
  case class Proposal(hash: Hash) extends PeerDeclaration

  @derive(eqv, show)
  case class MajoritySignature(signature: Signature) extends PeerDeclaration

}
