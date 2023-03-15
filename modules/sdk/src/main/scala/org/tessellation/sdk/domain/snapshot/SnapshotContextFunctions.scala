package org.tessellation.sdk.domain.snapshot

import org.tessellation.security.signature.Signed

trait SnapshotContextFunctions[F[_], Artifact, Context] {
  def createContext(
    context: Context,
    lastArtifact: Artifact,
    signedArtifact: Signed[Artifact]
  ): F[Context]
}
