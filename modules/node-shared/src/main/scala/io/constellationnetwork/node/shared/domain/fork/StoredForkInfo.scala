package io.constellationnetwork.node.shared.domain.fork

import io.constellationnetwork.schema.peer.PeerId

import derevo.cats.{eqv, show}
import derevo.derive

@derive(eqv, show)
case class StoredForkInfo(id: PeerId, forkInfo: ForkInfo)
