package org.tessellation.sdk.infrastructure.cluster.services

import java.security.KeyPair

import cats.effect.kernel.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Applicative, MonadThrow}

import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.cluster._
import org.tessellation.schema.peer.{PeerId, RegistrationRequest, SignRequest}
import org.tessellation.sdk.config.types.HttpConfig
import org.tessellation.sdk.domain.cluster.services.Cluster
import org.tessellation.sdk.domain.cluster.storage.SessionStorage
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

object Cluster {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    httpConfig: HttpConfig,
    nodeId: PeerId,
    keyPair: KeyPair,
    sessionStorage: SessionStorage[F]
  ): Cluster[F] =
    new Cluster[F] {

      def getRegistrationRequest: F[RegistrationRequest] =
        for {
          session <- sessionStorage.getToken.flatMap {
            case Some(s) => Applicative[F].pure(s)
            case None    => MonadThrow[F].raiseError[SessionToken](SessionDoesNotExist)
          }
        } yield
          RegistrationRequest(
            nodeId,
            httpConfig.externalIp,
            httpConfig.publicHttp.port,
            httpConfig.p2pHttp.port,
            session
          )

      def signRequest(signRequest: SignRequest): F[Signed[SignRequest]] =
        signRequest.sign(keyPair)

    }

}
