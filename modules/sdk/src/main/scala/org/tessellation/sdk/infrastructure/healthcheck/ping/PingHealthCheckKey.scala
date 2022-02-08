package org.tessellation.sdk.infrastructure.healthcheck.ping

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.healthcheck.consensus.types.HealthCheckKey

case class PingHealthCheckKey(id: PeerId) extends HealthCheckKey
