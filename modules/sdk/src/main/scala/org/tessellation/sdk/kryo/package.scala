package org.tessellation.sdk

import org.tessellation.kernel.kryo.kernelKryoRegistrar
import org.tessellation.schema.kryo.schemaKryoRegistrar
import org.tessellation.sdk.domain.healthcheck.consensus.types.HealthCheckRoundId
import org.tessellation.sdk.infrastructure.healthcheck.ping._

package object kryo {

  val sdkKryoRegistrar: Map[Class[_], Int] = Map[Class[_], Int](
    classOf[PingConsensusHealthStatus] -> 400,
    classOf[PingHealthCheckKey] -> 401,
    classOf[HealthCheckRoundId] -> 402,
    classOf[PeerAvailable] -> 403,
    classOf[PeerUnavailable] -> 404,
    classOf[PeerUnknown] -> 405
  ) ++ schemaKryoRegistrar ++ kernelKryoRegistrar

}
