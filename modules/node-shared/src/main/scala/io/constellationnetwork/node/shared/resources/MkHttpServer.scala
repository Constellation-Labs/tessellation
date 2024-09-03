package io.constellationnetwork.node.shared.resources

import cats.effect.kernel.{Async, Resource}
import cats.syntax.show._

import io.constellationnetwork.node.shared.config.types.HttpServerConfig
import io.constellationnetwork.node.shared.resources.MkHttpServer.ServerName

import derevo.cats.show
import derevo.derive
import fs2.io.net.Network
import io.estatico.newtype.macros.newtype
import org.http4s.HttpApp
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait MkHttpServer[F[_]] {
  def newEmber(name: ServerName, cfg: HttpServerConfig, httpApp: HttpApp[F]): Resource[F, Server]
}

object MkHttpServer {

  def apply[F[_]: MkHttpServer]: MkHttpServer[F] = implicitly

  @derive(show)
  @newtype
  case class ServerName(value: String)

  private def logger[F[_]: Async] = Slf4jLogger.getLogger

  private def showEmberBanner[F[_]: Async](name: ServerName)(s: Server): F[Unit] =
    logger.info(s"HTTP Server name=${name.show} started at ${s.address}")

  implicit def forAsync[F[_]: Async: Network]: MkHttpServer[F] =
    (name: ServerName, cfg: HttpServerConfig, httpApp: HttpApp[F]) =>
      EmberServerBuilder
        .default[F]
        .withHost(cfg.host)
        .withPort(cfg.port)
        .withShutdownTimeout(cfg.shutdownTimeout)
        .withHttpApp(httpApp)
        .build
        .evalTap(showEmberBanner[F](name))
}
