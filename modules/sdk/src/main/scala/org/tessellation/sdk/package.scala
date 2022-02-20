package org.tessellation

import org.tessellation.kernel.kernelKryoRegistrar
import org.tessellation.sdk.domain.healthcheck.consensus.types.HealthCheckRoundId
import org.tessellation.sdk.infrastructure.healthcheck.ping._
import org.tessellation.shared.sharedKryoRegistrar

package object sdk {

  val sdkKryoRegistrar: Map[Class[_], Int] = Map[Class[_], Int](
    classOf[PingConsensusHealthStatus] -> 400,
    classOf[PingHealthCheckKey] -> 401,
    classOf[HealthCheckRoundId] -> 402,
    classOf[PeerAvailable] -> 403,
    classOf[PeerUnavailable] -> 404,
    classOf[PeerUnknown] -> 405
  ) ++ sharedKryoRegistrar ++ kernelKryoRegistrar
}
