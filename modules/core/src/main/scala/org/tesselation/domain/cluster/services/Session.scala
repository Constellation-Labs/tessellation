package org.tesselation.domain.cluster.services

import org.tesselation.schema.cluster.{SessionToken, TokenVerificationResult}
import org.tesselation.schema.peer.PeerId

trait Session[F[_]] {
  def createSession: F[SessionToken]
  def verifyToken(peer: PeerId, headerToken: Option[SessionToken]): F[TokenVerificationResult]
}
