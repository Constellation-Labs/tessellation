package org.tessellation.sdk.domain.consensus

import org.tessellation.security.signature.Signed

trait ConsensusFunctions[F[_], Event, Key, Artifact] {

  def triggerPredicate(last: (Key, Artifact), event: Event): Boolean

  def createProposalArtifact(last: (Key, Artifact), events: Set[Event]): F[(Artifact, Set[Event])]

  def consumeSignedMajorityArtifact(signedArtifact: Signed[Artifact]): F[Unit]

}
