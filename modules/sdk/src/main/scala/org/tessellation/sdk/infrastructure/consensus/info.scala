package org.tessellation.sdk.infrastructure.consensus

import org.tessellation.schema.peer.PeerInfo

import derevo.circe.magnolia._
import derevo.derive

object info {

  @derive(encoder)
  case class ConsensusInfo[Key](
    key: Key,
    peers: Set[PeerInfo]
  )
}
