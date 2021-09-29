package org.tesselation.schema

import java.util.UUID

import scala.util.control.NoStackTrace

import org.tesselation.optics.uuid
import org.tesselation.schema.node.NodeState
import org.tesselation.schema.peer.{P2PContext, PeerId}

import com.comcast.ip4s.{Host, Port}
import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.estatico.newtype.macros.newtype

object cluster {

  @derive(decoder, encoder, show)
  case class PeerToJoin(id: PeerId, ip: Host, p2pPort: Port)

  object PeerToJoin {

    implicit def apply(p2PContext: P2PContext): PeerToJoin =
      PeerToJoin(p2PContext.id, p2PContext.ip, p2PContext.port)

    implicit def toP2PContext(peer: PeerToJoin): P2PContext =
      P2PContext(peer.ip, peer.p2pPort, peer.id)
  }

  case class NodeStateDoesNotAllowForJoining(nodeState: NodeState) extends NoStackTrace
  case class PeerIdInUse(id: PeerId) extends NoStackTrace
  case class PeerHostPortInUse(host: Host, p2pPort: Port) extends NoStackTrace

  @derive(decoder, encoder, eqv, show, uuid)
  @newtype
  case class SessionToken(value: UUID)

  case object SessionDoesNotExist extends NoStackTrace
  case object SessionAlreadyExists extends NoStackTrace

  trait TokenVerificationResult
  case object EmptyPeerToken extends TokenVerificationResult
  case object EmptyHeaderToken extends TokenVerificationResult
  case object TokenDontMatch extends TokenVerificationResult
  case object TokenValid extends TokenVerificationResult

  trait RegistrationRequestVerification extends NoStackTrace
  case object LocalHostNotPermitted extends RegistrationRequestVerification
  case object HostDifferentThanRemoteAddress extends RegistrationRequestVerification

  trait SignedRequestVerification
}
