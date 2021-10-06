package org.tesselation.resources

import cats.effect.Resource

import org.tesselation.config.types.AppConfig

import org.http4s.client.Client

sealed abstract class AppResources[F[_]](
  val client: Client[F]
)

object AppResources {

  def make[F[_]: MkHttpClient](cfg: AppConfig): Resource[F, AppResources[F]] =
    (MkHttpClient[F]
      .newEmber(cfg.httpConfig.client))
      .map(new AppResources[F](_) {})
}
