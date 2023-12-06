package org.tessellation.node.shared.infrastructure.healthcheck.ping

import org.tessellation.node.shared.domain.healthcheck.consensus.types.HealthCheckStatus
import org.tessellation.schema.peer.PeerId

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder)
sealed trait PingHealthCheckStatus extends HealthCheckStatus {
  def id: PeerId
}

case class PeerAvailable(id: PeerId) extends PingHealthCheckStatus
case class PeerUnavailable(id: PeerId) extends PingHealthCheckStatus
case class PeerUnknown(id: PeerId) extends PingHealthCheckStatus
case class PeerMismatch(id: PeerId) extends PingHealthCheckStatus
case class PeerCheckTimeouted(id: PeerId) extends PingHealthCheckStatus
case class PeerCheckUnexpectedError(id: PeerId) extends PingHealthCheckStatus
