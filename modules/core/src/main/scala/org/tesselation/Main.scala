package org.tesselation

import cats.effect.std.Supervisor
import cats.effect.{IO, IOApp}

import org.tesselation.config.Config
import org.tesselation.modules.{HttpApi, Services}
import org.tesselation.resources.MkHttpServer
import org.tesselation.resources.MkHttpServer.ServerName

import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Main extends IOApp.Simple {

  implicit val logger = Slf4jLogger.getLogger[IO]

  override def run: IO[Unit] =
    Config.load[IO].flatMap { cfg =>
      Logger[IO].info(s"Config loaded") >>
        Logger[IO].info(s"App environment: ${cfg.environment}") >>
        Supervisor[IO].use { _ =>
          val services = Services.make[IO]()
          val api = HttpApi.make[IO](services)

          MkHttpServer[IO].newEmber(ServerName("public"), cfg.publicHttpPort, api.httpApp).useForever
        }
    }

}
