package org.tessellation.sdk.infrastructure.consensus

import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.signature.Signature

import derevo.cats.{eqv, show}
import derevo.derive

object message {

  @derive(eqv, show)
  case class ConsensusEvent[E](value: E)

  @derive(eqv, show)
  case class ConsensusFacility[K](key: K, bound: Bound)

  @derive(eqv, show)
  case class ConsensusProposal[K](key: K, hash: Hash)

  @derive(eqv, show)
  case class MajoritySignature[K](key: K, signature: Signature)

  @derive(eqv, show)
  case class ConsensusArtifact[K, A](key: K, artifact: A)

}
