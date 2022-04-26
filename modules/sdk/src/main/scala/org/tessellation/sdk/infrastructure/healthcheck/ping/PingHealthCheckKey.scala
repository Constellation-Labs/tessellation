package org.tessellation.sdk.infrastructure.healthcheck.ping

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.healthcheck.consensus.types.HealthCheckKey

import com.comcast.ip4s.{Host, Port}

case class PingHealthCheckKey(id: PeerId, ip: Host, p2pPort: Port) extends HealthCheckKey
