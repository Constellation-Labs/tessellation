package org.tessellation.sdk.domain.consensus

import scala.util.control.NoStackTrace

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.consensus.ConsensusFunctions.InvalidArtifact
import org.tessellation.sdk.infrastructure.consensus.trigger.ConsensusTrigger
import org.tessellation.security.signature.Signed

trait ConsensusFunctions[F[_], Event, Key, Artifact, Context] {

  def triggerPredicate(event: Event): Boolean

  def facilitatorFilter(lastSignedArtifact: Signed[Artifact], lastContext: Context, peerId: PeerId): F[Boolean]

  def validateArtifact(
    lastSignedArtifact: Signed[Artifact],
    lastContext: Context,
    trigger: ConsensusTrigger,
    artifact: Artifact
  ): F[Either[InvalidArtifact, (Artifact, Context)]]

  def createProposalArtifact(
    lastKey: Key,
    lastArtifact: Signed[Artifact],
    lastContext: Context,
    trigger: ConsensusTrigger,
    events: Set[Event]
  ): F[(Artifact, Context, Set[Event])]

  def consumeSignedMajorityArtifact(signedArtifact: Signed[Artifact], context: Context): F[Unit]
}

object ConsensusFunctions {
  trait InvalidArtifact extends NoStackTrace
}
