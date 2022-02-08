package org.tessellation.sdk.domain.healthcheck.consensus.types

import org.tessellation.schema.peer.PeerId

trait HealthCheckKey {
  def id: PeerId
}
