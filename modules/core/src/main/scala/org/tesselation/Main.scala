package org.tesselation

import cats.effect.std.Supervisor
import cats.effect.{IO, IOApp}

import org.tesselation.config.Config
import org.tesselation.resources.MkHttpServer

import org.http4s.HttpApp
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Main extends IOApp.Simple {

  implicit val logger = Slf4jLogger.getLogger[IO]

  override def run: IO[Unit] =
    Config.load[IO].flatMap { cfg =>
      Logger[IO].info(s"Config loaded") >>
        Supervisor[IO].use { _ =>
          MkHttpServer[IO].newEmber(cfg.httpServerConfig, HttpApp.notFound).useForever
        }
    }

}
