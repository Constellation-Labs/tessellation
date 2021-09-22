package org.tesselation.schema

import java.security.PublicKey

import org.tesselation.keytool.security.KeyProvider.publicKeyToHex
import org.tesselation.schema.ID.Id
import org.tesselation.schema.cluster.SessionToken

import com.comcast.ip4s.{Host, Port}
import derevo.cats.{eqv, show}
import derevo.circe.magnolia._
import derevo.derive
import io.estatico.newtype.macros.newtype
import monocle.Iso

object peer {

  case class P2PContext(ip: Host, port: Port, id: PeerId)

  @derive(eqv, show, decoder, encoder, keyEncoder, keyDecoder)
  @newtype
  case class PeerId(value: String)

  object PeerId {

    val _Id: Iso[PeerId, Id] =
      Iso[PeerId, Id](peerId => Id(peerId.value))(id => PeerId(id.hex))

    val fromId: Id => PeerId = _Id.reverseGet

    def fromPublic(publicKey: PublicKey): PeerId =
      PeerId(publicKeyToHex(publicKey))
  }

  @derive(eqv, encoder, show)
  case class Peer(id: PeerId, ip: Host, publicPort: Port, p2pPort: Port)

  object Peer {
    implicit def toP2PContext(peer: Peer): P2PContext =
      P2PContext(peer.ip, peer.p2pPort, peer.id)
  }

  @derive(eqv, show)
  case class FullPeer(
    data: Peer
  )

  @derive(eqv, decoder, encoder, show)
  case class RegistrationRequest(
    id: PeerId,
    ip: Host,
    publicPort: Port,
    p2pPort: Port,
    session: SessionToken
  )

  @derive(eqv, decoder, encoder, show)
  case class JoinRequest(
    registrationRequest: RegistrationRequest
  )

}
