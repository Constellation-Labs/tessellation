package org.tessellation.sdk.infrastructure.consensus

import org.tessellation.sdk.infrastructure.consensus.declaration.PeerDeclaration

import derevo.cats.{eqv, show}
import derevo.derive

object message {

  @derive(eqv, show)
  case class ConsensusEvent[E](value: E)

  @derive(eqv, show)
  case class ConsensusPeerDeclaration[K, D <: PeerDeclaration](key: K, declaration: D)

  @derive(eqv, show)
  case class ConsensusArtifact[K, A](key: K, artifact: A)

}
