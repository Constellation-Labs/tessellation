package org.tessellation.schema

import java.security.PublicKey
import java.util.UUID

import cats.effect.kernel.Async
import cats.kernel.Order
import cats.syntax.functor._

import org.tessellation.schema.ID.Id
import org.tessellation.schema.address.Address
import org.tessellation.schema.cluster.{ClusterId, ClusterSessionToken, SessionToken}
import org.tessellation.schema.node.NodeState
import org.tessellation.security.SecurityProvider
import org.tessellation.security.hash.Hash
import org.tessellation.security.hex.Hex
import org.tessellation.security.key.ops._

import com.comcast.ip4s.{Host, Port}
import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia._
import derevo.derive
import derevo.scalacheck.arbitrary
import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops._
import monocle.macros.GenLens
import monocle.{Iso, Lens}

object peer {

  @derive(eqv, show, decoder, encoder)
  case class P2PContext(ip: Host, port: Port, id: PeerId)

  @derive(arbitrary, eqv, show, order, decoder, encoder, keyEncoder, keyDecoder)
  @newtype
  case class PeerId(value: Hex)

  object PeerId {

    val _Id: Iso[PeerId, Id] =
      Iso[PeerId, Id](peerId => Id(peerId.coerce))(id => PeerId(id.hex))

    implicit def ordering: Ordering[PeerId] = Order[PeerId].toOrdering

    val fromId: Id => PeerId = _Id.reverseGet

    def fromPublic(publicKey: PublicKey): PeerId =
      fromId(publicKey.toId)
  }

  implicit class PeerIdOps(peerId: PeerId) {
    def toId: Id = PeerId._Id.get(peerId)

    def toAddress[F[_]: Async](implicit sc: SecurityProvider[F]): F[Address] =
      peerId.value.toPublicKey
        .map(_.toAddress)
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

  @derive(eqv, encoder, decoder, show)
  case class L0Peer(id: PeerId, ip: Host, port: Port)

  object L0Peer {
    implicit def toP2PContext(l0Peer: L0Peer): P2PContext =
      P2PContext(l0Peer.ip, l0Peer.port, l0Peer.id)

    def fromPeer(p: Peer): L0Peer =
      L0Peer(p.id, p.ip, p.publicPort)
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
    session: SessionToken,
    clusterSession: ClusterSessionToken,
    clusterId: ClusterId,
    state: NodeState,
    whitelisting: Hash
  )

  @derive(eqv, decoder, encoder, show)
  case class SignRequest(value: UUID)

  object SignRequest

  @derive(eqv, decoder, encoder, show)
  case class JoinRequest(
    registrationRequest: RegistrationRequest
  )

}
