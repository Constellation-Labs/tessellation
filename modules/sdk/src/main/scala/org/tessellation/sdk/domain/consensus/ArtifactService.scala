package org.tessellation.sdk.domain.consensus

import org.tessellation.security.signature.Signed

trait ArtifactService[F[_], Artifact, Context] {
  def consume(signedArtifact: Signed[Artifact], context: Context): F[Unit]
}
