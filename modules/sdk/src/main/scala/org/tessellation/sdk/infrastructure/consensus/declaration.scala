package org.tessellation.sdk.infrastructure.consensus

import org.tessellation.schema.peer.PeerId
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.signature.Signature

object declaration {

  sealed trait PeerDeclaration

  case class Facility(upperBound: Bound, facilitators: Set[PeerId]) extends PeerDeclaration
  case class Proposal(hash: Hash) extends PeerDeclaration
  case class MajoritySignature(signature: Signature) extends PeerDeclaration

}
