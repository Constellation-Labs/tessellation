package io.constellationnetwork.node.shared.domain.cluster.services

import io.constellationnetwork.schema.cluster.{SessionToken, TokenVerificationResult}
import io.constellationnetwork.schema.peer.PeerId

trait Session[F[_]] {
  def createSession: F[SessionToken]
  def verifyToken(peer: PeerId, headerToken: Option[SessionToken]): F[TokenVerificationResult]
}
