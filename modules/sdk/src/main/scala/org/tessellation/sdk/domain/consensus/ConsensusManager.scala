package org.tessellation.sdk.domain.consensus

import org.tessellation.sdk.infrastructure.consensus.ConsensusResources
import org.tessellation.security.signature.Signed

trait ConsensusManager[F[_], Key, Artifact, Context] {

  def startObserving: F[Unit]
  def startFacilitatingAfter(lastKey: Key, lastArtifact: Signed[Artifact], lastContext: Context): F[Unit]

  def withdrawFromConsensus: F[Unit]
  def facilitateOnEvent: F[Unit]
  def checkForStateUpdate(key: Key)(resources: ConsensusResources[Artifact]): F[Unit]

}
