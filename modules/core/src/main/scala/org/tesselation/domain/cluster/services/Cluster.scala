package org.tesselation.domain.cluster.services
import org.tesselation.schema.peer.{RegistrationRequest, SignRequest}
import org.tesselation.security.signature.Signed

trait Cluster[F[_]] {
  def getRegistrationRequest: F[RegistrationRequest]
  def signRequest(signRequest: SignRequest): F[Signed[SignRequest]]
}
