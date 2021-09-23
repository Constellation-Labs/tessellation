package org.tesselation.domain.cluster.services

import org.tesselation.schema.peer.RegistrationRequest

trait Cluster[F[_]] {
  def getRegistrationRequest: F[RegistrationRequest]
}
