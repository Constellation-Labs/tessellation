package org.tessellation.sdk.domain.cluster.services

import org.tessellation.schema.cluster.{SessionToken, TokenVerificationResult}
import org.tessellation.schema.peer.PeerId

import com.comcast.ip4s.Host

trait Session[F[_]] {
  def createSession: F[SessionToken]
  def verifyToken(peer: PeerId, headerToken: Option[SessionToken]): F[TokenVerificationResult]
  def verifyToken(host: Host, headerToken: Option[SessionToken]): F[TokenVerificationResult]
}
