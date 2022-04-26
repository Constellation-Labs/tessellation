package org.tessellation.sdk.infrastructure.healthcheck.ping

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.healthcheck.consensus.types.HealthCheckStatus

sealed trait PingHealthCheckStatus extends HealthCheckStatus

case class PeerAvailable(id: PeerId) extends PingHealthCheckStatus
case class PeerUnavailable(id: PeerId) extends PingHealthCheckStatus
case class PeerUnknown(id: PeerId) extends PingHealthCheckStatus
case class PeerMismatch(id: PeerId) extends PingHealthCheckStatus
case class PeerCheckTimeouted(id: PeerId) extends PingHealthCheckStatus
case class PeerCheckUnexpectedError(id: PeerId) extends PingHealthCheckStatus
