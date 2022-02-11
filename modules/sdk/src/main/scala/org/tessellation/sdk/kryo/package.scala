package org.tessellation.sdk

import org.tessellation.kernel.kryo.kernelKryoRegistrar
import org.tessellation.schema.kryo.schemaKryoRegistrar
import org.tessellation.sdk.domain.healthcheck.consensus.types.HealthCheckRoundId
import org.tessellation.sdk.infrastructure.healthcheck.ping._

package object kryo {

  val sdkKryoRegistrar: Map[Class[_], Int] = Map[Class[_], Int](
    classOf[PingConsensusHealthStatus] -> 600,
    classOf[PingHealthCheckKey] -> 601,
    classOf[HealthCheckRoundId] -> 602,
    classOf[PeerAvailable] -> 603,
    classOf[PeerUnavailable] -> 604,
    classOf[PeerUnknown] -> 605
  ) ++ schemaKryoRegistrar ++ kernelKryoRegistrar

}
