package io.constellationnetwork.ext.http4s

import cats.implicits.catsSyntaxOptionId

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

object PeerIdVar {
  def unapply(str: String): Option[PeerId] = PeerId(Hex(str)).some
}
