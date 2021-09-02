package org.tessellation.aci

import cats.effect._
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.{Router, Server}
import org.http4s.syntax.kleisli._

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.global

object ACIApp extends IOApp {
  implicit val ctx: ContextShift[IO] = IO.contextShift(ExecutionContext.global)

  implicit val cc: ConcurrentEffect[IO] = IO.ioConcurrentEffect

  val context: ACIContext[IO] = new ACIContext[IO]

  override def run(args: List[String]): IO[ExitCode] =
    for {
      _ <- context.repository.initDb
      app <- ACIServer
        .resource[IO](context)
        .use(_ => IO.never)
        .as(ExitCode.Success)
    } yield app
}

object ACIServer {

  def resource[F[_]: Concurrent: ConcurrentEffect: Timer: ContextShift](
    context: ACIContext[F]
  ): Resource[F, Server[F]] = {
    val httpApp = Router("/" -> context.aciRoutes).orNotFound

    BlazeServerBuilder[F](global)
      .bindHttp(8080)
      .withHttpApp(httpApp)
      .resource
  }
}
