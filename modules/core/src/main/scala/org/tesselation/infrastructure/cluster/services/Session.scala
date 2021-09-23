package org.tesselation.infrastructure.cluster.services

import cats.effect.kernel.Async

import org.tesselation.domain.cluster.services.Session
import org.tesselation.domain.cluster.storage.{ClusterStorage, SessionStorage}
import org.tesselation.schema.cluster.{SessionToken, TokenVerificationResult}
import org.tesselation.schema.peer.PeerId

object Session {

  def make[F[_]: Async](sessionStorage: SessionStorage[F], clusterStorage: ClusterStorage[F]): Session[F] =
    new Session[F] {
      def createSession: F[SessionToken] = sessionStorage.createToken

      def verifyToken(peer: PeerId, headerToken: Option[SessionToken]): F[TokenVerificationResult] = ???
    }
}
