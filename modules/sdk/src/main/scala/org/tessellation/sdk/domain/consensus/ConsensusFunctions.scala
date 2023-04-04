package org.tessellation.sdk.domain.consensus

import scala.util.control.NoStackTrace

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.infrastructure.consensus.trigger.ConsensusTrigger
import org.tessellation.security.signature.Signed

trait ConsensusFunctions[F[_], Event, Key, Artifact, Context] extends ConsensusValidator[F, Artifact, Context] {

  def triggerPredicate(event: Event): Boolean

  def facilitatorFilter(lastSignedArtifact: Signed[Artifact], lastContext: Context, peerId: PeerId): F[Boolean]

  def createProposalArtifact(
    lastKey: Key,
    lastArtifact: Signed[Artifact],
    context: Context,
    trigger: ConsensusTrigger,
    events: Set[Event]
  ): F[(Artifact, Context, Set[Event])]
}
