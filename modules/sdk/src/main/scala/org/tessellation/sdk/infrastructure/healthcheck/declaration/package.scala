package org.tessellation.sdk.infrastructure.healthcheck

package object declaration {

  type Key[K] = PeerDeclarationHealthCheckKey[K]
  type Health = PeerDeclarationHealth
  type Status[K] = PeerDeclarationConsensusHealthStatus[K]
  type Decision = PeerDeclarationHealthDecision

}
