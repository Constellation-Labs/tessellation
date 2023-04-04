package org.tessellation.sdk.domain.consensus

import scala.util.control.NoStackTrace
import org.tessellation.security.signature.Signed

trait ConsensusValidator[F[_], Artifact, Context] {
  def validateArtifact(lastSignedArtifact: Signed[Artifact], lastContext: Context)(
    artifact: Artifact
  ): F[Either[ConsensusValidator.InvalidArtifact, (Artifact, Context)]]
}

object ConsensusValidator {
  trait InvalidArtifact extends NoStackTrace
}
