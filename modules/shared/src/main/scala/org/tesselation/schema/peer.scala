package org.tesselation.schema

import com.comcast.ip4s.{Host, Port}
import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{keyDecoder, keyEncoder}
import derevo.derive
import io.estatico.newtype.macros.newtype

object peer {

  @derive(eqv, show, keyEncoder, keyDecoder)
  @newtype
  case class PeerID(value: String) // TODO: isomorphic to Id(hex)

  @derive(eqv, show)
  case class Peer(id: PeerID, ip: Host, publicPort: Port, p2pPort: Port, healthcheckPort: Port)

  @derive(eqv, show)
  case class FullPeer(
    data: Peer
  )

}
