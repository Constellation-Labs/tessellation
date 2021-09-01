package org.tesselation

import cats.effect._
import cats.effect.std.Supervisor

import org.tesselation.config.Config
import org.tesselation.modules.{HttpApi, Services}
import org.tesselation.resources.MkHttpServer
import org.tesselation.resources.MkHttpServer.ServerName

import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Main extends IOApp {

  implicit val logger = Slf4jLogger.getLogger[IO]

  def logThread(name: String): Unit =
    println(s"$name: ${Thread.currentThread().getName}")

  override def run(args: List[String]): IO[ExitCode] =
    Config.load[IO].flatMap { cfg =>
      Logger[IO].info(s"Config loaded") >>
        Logger[IO].info(s"App environment: ${cfg.environment}") >>
        Supervisor[IO].use { _ =>
          (for {
            services <- Resource.eval { Services.make[IO]() }
            api = HttpApi.make[IO](services)
            _ <- MkHttpServer[IO].newEmber(ServerName("public"), cfg.publicHttp, api.publicApp)
            _ <- MkHttpServer[IO].newEmber(ServerName("p2p"), cfg.p2pHttp, api.p2pApp)
            _ <- MkHttpServer[IO].newEmber(ServerName("cli"), cfg.cliHttp, api.cliApp)
          } yield ()).useForever

        }
    }

}
