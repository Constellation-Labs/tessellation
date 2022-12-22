package org.tessellation.sdk.resources

import java.security.PrivateKey

import cats.effect.{Async, Resource}

import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.security.SecurityProvider
import org.tessellation.sdk.config.types.SdkConfig
import org.tessellation.sdk.domain.cluster.storage.SessionStorage
import org.tessellation.sdk.http.p2p.middleware.PeerAuthMiddleware

import org.http4s.client.Client
import org.http4s.client.middleware.{RequestLogger, ResponseLogger}

sealed abstract class SdkResources[F[_]](
  val client: Client[F]
)

object SdkResources {

  def make[F[_]: MkHttpClient: Async: SecurityProvider](
    cfg: SdkConfig,
    privateKey: PrivateKey,
    sessionStorage: SessionStorage[F],
    selfId: PeerId
  ): Resource[F, SdkResources[F]] =
    MkHttpClient[F]
      .newEmber(cfg.httpConfig.client)
      .map(
        PeerAuthMiddleware.requestSignerMiddleware[F](_, privateKey, sessionStorage, selfId)
      )
      .map { client =>
        ResponseLogger(logHeaders = true, logBody = false)(RequestLogger(logHeaders = true, logBody = false)(client))
      }
      .map(new SdkResources[F](_) {})
}
