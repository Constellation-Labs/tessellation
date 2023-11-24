package org.tessellation.sdk.domain.cluster.services

import org.tessellation.schema.cluster.{SessionToken, TokenVerificationResult}
import org.tessellation.schema.peer.PeerId

trait Session[F[_]] {
  def createSession: F[SessionToken]
  def verifyToken(peer: PeerId, headerToken: Option[SessionToken]): F[TokenVerificationResult]
}
