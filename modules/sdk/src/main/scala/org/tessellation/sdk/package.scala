package org.tessellation

import org.tessellation.kernel.kernelKryoRegistrar
import org.tessellation.sdk.domain.healthcheck.consensus.types.HealthCheckRoundId
import org.tessellation.sdk.infrastructure.healthcheck.ping._
import org.tessellation.shared.sharedKryoRegistrar

package object sdk {

  val sdkKryoRegistrar: Map[Class[_], Int] = Map[Class[_], Int](
    classOf[PingConsensusHealthStatus] -> 500,
    classOf[PingHealthCheckKey] -> 501,
    classOf[HealthCheckRoundId] -> 502,
    classOf[PeerAvailable] -> 503,
    classOf[PeerUnavailable] -> 504,
    classOf[PeerUnknown] -> 505
  ) ++ sharedKryoRegistrar ++ kernelKryoRegistrar
}
