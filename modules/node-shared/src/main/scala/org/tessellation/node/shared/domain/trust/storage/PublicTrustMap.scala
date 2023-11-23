package org.tessellation.node.shared.domain.trust.storage

import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.trust.PublicTrust

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(eqv, encoder, decoder, show)
case class PublicTrustMap(
  value: Map[PeerId, PublicTrust]
) {
  def add(peerId: PeerId, publicTrust: PublicTrust): PublicTrustMap =
    PublicTrustMap(value + (peerId -> publicTrust))
}

object PublicTrustMap {

  val empty: PublicTrustMap = PublicTrustMap(Map.empty)

}
