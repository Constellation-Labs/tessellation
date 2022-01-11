package org.tessellation.sdk.resources

import cats.effect.Resource

import org.tessellation.sdk.config.types.SdkConfig

import org.http4s.client.Client

sealed abstract class SdkResources[F[_]](
  val client: Client[F]
)

object SdkResources {

  def make[F[_]: MkHttpClient](cfg: SdkConfig): Resource[F, SdkResources[F]] =
    (MkHttpClient[F].newEmber(cfg.httpConfig.client)).map(new SdkResources[F](_) {})
}
