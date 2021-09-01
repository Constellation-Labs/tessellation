package org.tesselation.schema

import org.tesselation.schema.ID.Id

import com.comcast.ip4s.{Host, Port}
import derevo.cats.{eqv, show}
import derevo.circe.magnolia._
import derevo.derive
import io.estatico.newtype.macros.newtype
import monocle.Iso

object peer {

  @derive(eqv, show, decoder, encoder, keyEncoder, keyDecoder)
  @newtype
  case class PeerId(value: String)

  object PeerId {

    val _Id: Iso[PeerId, Id] =
      Iso[PeerId, Id](peerId => Id(peerId.value))(id => PeerId(id.hex))

    val fromId: Id => PeerId = _Id.reverseGet
  }

  @derive(eqv, encoder, show)
  case class Peer(id: PeerId, ip: Host, publicPort: Port, p2pPort: Port)

  @derive(eqv, show)
  case class FullPeer(
    data: Peer
  )

}
