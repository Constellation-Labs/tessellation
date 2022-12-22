package org.tessellation.schema

import java.util.UUID

import scala.util.control.NoStackTrace

import org.tessellation.optics.uuid
import org.tessellation.schema.generation.Generation
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.{P2PContext, PeerId}

import com.comcast.ip4s.{Host, Port}
import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.string.Uuid
import io.estatico.newtype.macros.newtype

object cluster {

  @derive(encoder, decoder, eqv, show, uuid)
  @newtype
  final case class ClusterId(value: UUID)

  object ClusterId {
    def apply(uuidString: String Refined Uuid): ClusterId = ClusterId.apply(UUID.fromString(uuidString))
  }

  @derive(encoder, decoder, eqv, show)
  @newtype
  final case class ClusterSessionToken(value: Generation)

  @derive(decoder, encoder, show)
  case class PeerToJoin(id: PeerId, ip: Host, p2pPort: Port)

  object PeerToJoin {

    implicit def apply(p2PContext: P2PContext): PeerToJoin =
      PeerToJoin(p2PContext.id, p2PContext.ip, p2PContext.port)

    implicit def toP2PContext(peer: PeerToJoin): P2PContext =
      P2PContext(peer.ip, peer.p2pPort, peer.id)
  }

  case class NodeStateDoesNotAllowForJoining(nodeState: NodeState) extends NoStackTrace
  case class PeerAlreadyConnected(id: PeerId, host: Host, p2pPort: Port, session: SessionToken) extends NoStackTrace
  case class PeerNotInSeedlist(id: PeerId) extends NoStackTrace

  @derive(decoder, encoder, order, show)
  @newtype
  case class SessionToken(value: Generation)

  case object SessionDoesNotExist extends NoStackTrace
  case object SessionAlreadyExists extends NoStackTrace
  case object ClusterSessionAlreadyExists extends NoStackTrace
  case object NodeNotInCluster extends NoStackTrace

  trait TokenVerificationResult
  case object EmptyHeaderToken extends TokenVerificationResult
  case object TokenDoesntMatch extends TokenVerificationResult
  case object TokenValid extends TokenVerificationResult

  case object HandshakeSignatureNotValid extends NoStackTrace

  trait RegistrationRequestValidation extends NoStackTrace
  case object LocalHostNotPermitted extends RegistrationRequestValidation
  case object InvalidRemoteAddress extends RegistrationRequestValidation
  case object IdDuplicationFound extends RegistrationRequestValidation
  case object SeedlistDoesNotMatch extends RegistrationRequestValidation
  case object CollateralNotSatisfied extends RegistrationRequestValidation
  case object VersionMismatch extends RegistrationRequestValidation

  trait ClusterVerificationResult extends NoStackTrace
  case object ClusterIdDoesNotMatch extends ClusterVerificationResult
  case object ClusterSessionDoesNotExist extends ClusterVerificationResult
  case object ClusterSessionDoesNotMatch extends ClusterVerificationResult
  case object NotInCluster extends ClusterVerificationResult
}
