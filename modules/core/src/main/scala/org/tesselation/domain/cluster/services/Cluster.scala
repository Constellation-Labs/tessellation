package org.tesselation.domain.cluster.services

import org.tesselation.crypto.Signed
import org.tesselation.schema.peer.{RegistrationRequest, SignRequest}

trait Cluster[F[_]] {
  def getRegistrationRequest: F[RegistrationRequest]
  def signRequest(signRequest: SignRequest): F[Signed[SignRequest]]
}
