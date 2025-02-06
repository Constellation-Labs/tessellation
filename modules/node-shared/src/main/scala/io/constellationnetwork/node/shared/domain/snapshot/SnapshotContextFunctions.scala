package io.constellationnetwork.node.shared.domain.snapshot

import io.constellationnetwork.schema.GlobalIncrementalSnapshot
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher}

trait SnapshotContextFunctions[F[_], Artifact, Context] {
  def createContext(
    context: Context,
    lastArtifact: Signed[Artifact],
    signedArtifact: Signed[Artifact],
    lastGlobalSnapshots: Option[List[Hashed[GlobalIncrementalSnapshot]]]
  )(implicit hasher: Hasher[F]): F[Context]
}
