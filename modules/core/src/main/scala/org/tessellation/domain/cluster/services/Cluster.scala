package org.tessellation.domain.cluster.services
import org.tessellation.schema.peer.{RegistrationRequest, SignRequest}
import org.tessellation.security.signature.Signed

trait Cluster[F[_]] {
  def getRegistrationRequest: F[RegistrationRequest]
  def signRequest(signRequest: SignRequest): F[Signed[SignRequest]]
}
