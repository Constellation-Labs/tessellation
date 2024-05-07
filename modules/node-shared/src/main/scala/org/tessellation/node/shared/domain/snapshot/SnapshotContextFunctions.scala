package org.tessellation.node.shared.domain.snapshot

import org.tessellation.security.Hasher
import org.tessellation.security.signature.Signed

trait SnapshotContextFunctions[F[_], Artifact, Context] {
  def createContext(
    context: Context,
    lastArtifact: Signed[Artifact],
    signedArtifact: Signed[Artifact]
  )(implicit hasher: Hasher[F]): F[Context]
}
