package io.constellationnetwork.node.shared.domain.snapshot

import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed

trait SnapshotContextFunctions[F[_], Artifact, Context] {
  def createContext(
    context: Context,
    lastArtifact: Signed[Artifact],
    signedArtifact: Signed[Artifact]
  )(implicit hasher: Hasher[F]): F[Context]
}
