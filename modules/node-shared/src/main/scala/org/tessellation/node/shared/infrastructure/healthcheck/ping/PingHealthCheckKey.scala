package org.tessellation.node.shared.infrastructure.healthcheck.ping

import org.tessellation.node.shared.domain.healthcheck.consensus.types.HealthCheckKey
import org.tessellation.schema._
import org.tessellation.schema.cluster.SessionToken
import org.tessellation.schema.peer.PeerId

import com.comcast.ip4s.{Host, Port}
import derevo.cats.show
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder, show)
case class PingHealthCheckKey(id: PeerId, ip: Host, p2pPort: Port, session: SessionToken) extends HealthCheckKey
