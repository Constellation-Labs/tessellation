package org.tessellation.sdk.domain.consensus

import scala.util.control.NoStackTrace

import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.security.signature.Signed
import org.tessellation.sdk.domain.consensus.ConsensusFunctions.InvalidArtifact
import org.tessellation.sdk.infrastructure.consensus.trigger.ConsensusTrigger

trait ConsensusFunctions[F[_], Event, Key, Artifact] {

  def triggerPredicate(event: Event): Boolean

  def facilitatorFilter(lastSignedArtifact: Signed[Artifact], peerId: PeerId): F[Boolean]

  def validateArtifact(lastSignedArtifact: Signed[Artifact], trigger: ConsensusTrigger)(
    artifact: Artifact
  ): F[Either[InvalidArtifact, Artifact]]

  def createProposalArtifact(
    lastKey: Key,
    lastSignedArtifact: Signed[Artifact],
    trigger: ConsensusTrigger,
    events: Set[Event]
  ): F[(Artifact, Set[Event])]

  def consumeSignedMajorityArtifact(signedArtifact: Signed[Artifact]): F[Unit]

}

object ConsensusFunctions {
  trait InvalidArtifact extends NoStackTrace
}
