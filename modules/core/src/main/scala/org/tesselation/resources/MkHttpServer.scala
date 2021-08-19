package org.tesselation.resources

import cats.effect.kernel.{Async, Resource}
import cats.syntax.all._

import org.tesselation.resources.MkHttpServer.ServerName

import com.comcast.ip4s.{IpLiteralSyntax, Port}
import derevo.cats.show
import derevo.derive
import io.estatico.newtype.macros.newtype
import org.http4s.HttpApp
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import org.http4s.server.defaults.Banner
import org.typelevel.log4cats.Logger

trait MkHttpServer[F[_]] {
  def newEmber(name: ServerName, port: Port, httpApp: HttpApp[F]): Resource[F, Server]
}

object MkHttpServer {
  def apply[F[_]: MkHttpServer]: MkHttpServer[F] = implicitly

  @derive(show)
  @newtype
  case class ServerName(value: String)

  private def showEmberBanner[F[_]: Logger](name: ServerName)(s: Server): F[Unit] =
    Logger[F].info(s"\n${Banner.mkString("\n")}\nHTTP Server name=${name.show} started at ${s.address}")

  implicit def forAsyncLogger[F[_]: Async: Logger]: MkHttpServer[F] =
    (name: ServerName, port: Port, httpApp: HttpApp[F]) =>
      EmberServerBuilder
        .default[F]
        .withHost(host"0.0.0.0")
        .withPort(port)
        .withHttpApp(httpApp)
        .build
        .evalTap(showEmberBanner[F](name))
}
