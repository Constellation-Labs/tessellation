package io.constellationnetwork.node.shared.resources

import java.security.PrivateKey

import cats.effect.{Async, Resource}

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.config.types.{HttpClientConfig, SharedConfig}
import io.constellationnetwork.node.shared.domain.cluster.storage.SessionStorage
import io.constellationnetwork.node.shared.http.p2p.middlewares.{ClientMetricsMiddleware, PeerAuthMiddleware}
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.SecurityProvider

import org.http4s.client.Client
import org.http4s.client.middleware.{RequestLogger, ResponseLogger}

sealed abstract class SharedResources[F[_]](
  val client: Client[F],
  val gossipClient: Client[F]
)

object SharedResources {

  /** Lightweight HTTP client config for the event gossip daemon.
    *
    * Uses shorter timeouts (10s vs 60s) to prevent gossip requests from holding connections open during slow responses. Isolated from the
    * main client pool so gossip HTTP traffic cannot starve consensus P2P connections on resource-constrained environments (e.g. CI runners
    * with 4 vCPU running 14+ JVM containers).
    */
  private val gossipHttpClientConfig: HttpClientConfig = HttpClientConfig(
    timeout = 10.seconds,
    idleTimeInPool = 10.seconds
  )

  private def buildClient[F[_]: MkHttpClient: Async: SecurityProvider: Metrics](
    cfg: HttpClientConfig,
    privateKey: PrivateKey,
    sessionStorage: SessionStorage[F],
    selfId: PeerId
  ): Resource[F, Client[F]] =
    MkHttpClient[F]
      .newEmber(cfg)
      .map(
        PeerAuthMiddleware.requestSignerMiddleware[F](_, privateKey, sessionStorage, selfId)
      )
      .map(
        ClientMetricsMiddleware.fromClient[F](_)
      )
      .map { client =>
        ResponseLogger(logHeaders = true, logBody = false)(RequestLogger(logHeaders = true, logBody = false)(client))
      }

  def make[F[_]: MkHttpClient: Async: SecurityProvider: Metrics](
    cfg: SharedConfig,
    privateKey: PrivateKey,
    sessionStorage: SessionStorage[F],
    selfId: PeerId
  ): Resource[F, SharedResources[F]] =
    for {
      mainClient <- buildClient(cfg.http.client, privateKey, sessionStorage, selfId)
      gossip <- buildClient(gossipHttpClientConfig, privateKey, sessionStorage, selfId)
    } yield new SharedResources[F](mainClient, gossip) {}
}
