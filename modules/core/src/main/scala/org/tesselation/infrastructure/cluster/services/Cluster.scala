package org.tesselation.infrastructure.cluster.services

import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Applicative, MonadThrow}

import org.tesselation.config.types.AppConfig
import org.tesselation.domain.cluster.services.Cluster
import org.tesselation.domain.cluster.storage.SessionStorage
import org.tesselation.schema.cluster._
import org.tesselation.schema.peer.{PeerId, RegistrationRequest}

object Cluster {

  def make[F[_]: MonadThrow](
    cfg: AppConfig,
    nodeId: PeerId,
    sessionStorage: SessionStorage[F]
  ): Cluster[F] =
    new Cluster[F] {

      def getRegistrationRequest: F[RegistrationRequest] =
        for {
          session <- sessionStorage.getToken.flatMap {
            case Some(s) => Applicative[F].pure(s)
            case None    => MonadThrow[F].raiseError[SessionToken](SessionDoesNotExist)
          }
        } yield RegistrationRequest(nodeId, cfg.externalIp, cfg.publicHttp.port, cfg.p2pHttp.port, session)

    }

}
