package org.tessellation.resources

import cats.effect.kernel.{Async, Resource}
import cats.syntax.show._

import org.tessellation.config.types.HttpServerConfig
import org.tessellation.resources.MkHttpServer.ServerName

import derevo.cats.show
import derevo.derive
import io.estatico.newtype.macros.newtype
import org.http4s.HttpApp
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import org.typelevel.log4cats.Logger

trait MkHttpServer[F[_]] {
  def newEmber(name: ServerName, cfg: HttpServerConfig, httpApp: HttpApp[F]): Resource[F, Server]
}

object MkHttpServer {
  def apply[F[_]: MkHttpServer]: MkHttpServer[F] = implicitly

  @derive(show)
  @newtype
  case class ServerName(value: String)

  private def showEmberBanner[F[_]: Logger](name: ServerName)(s: Server): F[Unit] =
    Logger[F].info(s"HTTP Server name=${name.show} started at ${s.address}")

  implicit def forAsyncLogger[F[_]: Async: Logger]: MkHttpServer[F] =
    (name: ServerName, cfg: HttpServerConfig, httpApp: HttpApp[F]) =>
      EmberServerBuilder
        .default[F]
        .withHost(cfg.host)
        .withPort(cfg.port)
        .withHttpApp(httpApp)
        .build
        .evalTap(showEmberBanner[F](name))
}
