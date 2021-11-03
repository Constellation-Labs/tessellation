package org.tesselation.schema

import java.security.PublicKey
import java.util.UUID

import org.tesselation.schema.ID.Id
import org.tesselation.schema.cluster.SessionToken
import org.tesselation.schema.node.NodeState
import org.tesselation.security.hex.Hex
import org.tesselation.security.key.ops._

import com.comcast.ip4s.{Host, Port}
import derevo.cats.{eqv, show}
import derevo.circe.magnolia._
import derevo.derive
import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops._
import monocle.macros.GenLens
import monocle.{Iso, Lens}

object peer {

  @derive(eqv, show, decoder, encoder)
  case class P2PContext(ip: Host, port: Port, id: PeerId)

  @derive(eqv, show, decoder, encoder, keyEncoder, keyDecoder)
  @newtype
  case class PeerId(value: Hex)

  object PeerId {

    val _Id: Iso[PeerId, Id] =
      Iso[PeerId, Id](peerId => Id(peerId.coerce))(id => PeerId(id.hex))

    val fromId: Id => PeerId = _Id.reverseGet

    def fromPublic(publicKey: PublicKey): PeerId =
      PeerId(publicKey.toHex)
  }

  @derive(eqv, encoder, decoder, show)
  case class Peer(
    id: PeerId,
    ip: Host,
    publicPort: Port,
    p2pPort: Port,
    session: SessionToken,
    state: NodeState
  )

  object Peer {
    implicit def toP2PContext(peer: Peer): P2PContext =
      P2PContext(peer.ip, peer.p2pPort, peer.id)

    val _State: Lens[Peer, NodeState] = GenLens[Peer](_.state)
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
  case class SignRequest(value: UUID)

  object SignRequest

  @derive(eqv, decoder, encoder, show)
  case class JoinRequest(
    registrationRequest: RegistrationRequest
  )

}
