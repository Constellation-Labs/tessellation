package org.tessellation.node.shared.domain.consensus

import scala.util.control.NoStackTrace

import org.tessellation.node.shared.domain.consensus.ConsensusFunctions.InvalidArtifact
import org.tessellation.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.Hasher
import org.tessellation.security.signature.Signed

trait ConsensusFunctions[F[_], Event, Key, Artifact, Context] {

  def triggerPredicate(event: Event): Boolean

  def facilitatorFilter(lastSignedArtifact: Signed[Artifact], lastContext: Context, peerId: PeerId): F[Boolean]

  def validateArtifact(
    lastSignedArtifact: Signed[Artifact],
    lastContext: Context,
    trigger: ConsensusTrigger,
    artifact: Artifact,
    facilitators: Set[PeerId]
  )(implicit hasher: Hasher[F]): F[Either[InvalidArtifact, (Artifact, Context)]]

  def createProposalArtifact(
    lastKey: Key,
    lastArtifact: Signed[Artifact],
    lastContext: Context,
    lastArtifactHasher: Hasher[F],
    trigger: ConsensusTrigger,
    events: Set[Event],
    facilitators: Set[PeerId]
  )(implicit hasher: Hasher[F]): F[(Artifact, Context, Set[Event])]
}

object ConsensusFunctions {
  trait InvalidArtifact extends NoStackTrace
}
