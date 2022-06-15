package org.tessellation.sdk.infrastructure.healthcheck.ping

import org.tessellation.schema._
import org.tessellation.schema.cluster.SessionToken
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.healthcheck.consensus.types.HealthCheckKey

import com.comcast.ip4s.{Host, Port}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder)
case class PingHealthCheckKey(id: PeerId, ip: Host, p2pPort: Port, session: SessionToken) extends HealthCheckKey
