package org.tessellation.sdk.domain.consensus

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.infrastructure.consensus.trigger.ConsensusTrigger
import org.tessellation.security.signature.Signed

trait ConsensusFunctions[F[_], Event, Key, Artifact] {

  def triggerPredicate(event: Event): Boolean

  def facilitatorFilter(lastSignedArtifact: Signed[Artifact], peerId: PeerId): F[Boolean]

  def createProposalArtifact(
    lastKey: Key,
    lastSignedArtifact: Signed[Artifact],
    trigger: ConsensusTrigger,
    events: Set[Event]
  ): F[(Artifact, Set[Event])]

  def consumeSignedMajorityArtifact(signedArtifact: Signed[Artifact]): F[Unit]

}
