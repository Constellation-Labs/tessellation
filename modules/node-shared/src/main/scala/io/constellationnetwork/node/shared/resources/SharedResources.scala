package io.constellationnetwork.node.shared.resources

import java.security.PrivateKey

import cats.effect.{Async, Resource}

import io.constellationnetwork.node.shared.config.types.SharedConfig
import io.constellationnetwork.node.shared.domain.cluster.storage.SessionStorage
import io.constellationnetwork.node.shared.http.p2p.middlewares.PeerAuthMiddleware
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.SecurityProvider

import org.http4s.client.Client
import org.http4s.client.middleware.{RequestLogger, ResponseLogger}

sealed abstract class SharedResources[F[_]](
  val client: Client[F]
)

object SharedResources {

  def make[F[_]: MkHttpClient: Async: SecurityProvider](
    cfg: SharedConfig,
    privateKey: PrivateKey,
    sessionStorage: SessionStorage[F],
    selfId: PeerId
  ): Resource[F, SharedResources[F]] =
    MkHttpClient[F]
      .newEmber(cfg.http.client)
      .map(
        PeerAuthMiddleware.requestSignerMiddleware[F](_, privateKey, sessionStorage, selfId)
      )
      .map { client =>
        ResponseLogger(logHeaders = true, logBody = false)(RequestLogger(logHeaders = true, logBody = false)(client))
      }
      .map(new SharedResources[F](_) {})
}
