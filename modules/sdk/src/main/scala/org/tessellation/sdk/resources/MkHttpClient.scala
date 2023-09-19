package org.tessellation.sdk.resources

import cats.effect.{Async, Resource}

import org.tessellation.sdk.config.types.HttpClientConfig

import fs2.io.net.Network
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder

trait MkHttpClient[F[_]] {
  def newEmber(cfg: HttpClientConfig): Resource[F, Client[F]]
}

object MkHttpClient {
  def apply[F[_]: MkHttpClient]: MkHttpClient[F] = implicitly

  implicit def forAsync[F[_]: Async: Network]: MkHttpClient[F] =
    (cfg: HttpClientConfig) =>
      EmberClientBuilder
        .default[F]
        .withTimeout(cfg.timeout)
        .withIdleTimeInPool(cfg.idleTimeInPool)
        .build
}
