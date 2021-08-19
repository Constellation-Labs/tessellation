package org.tesselation.modules

import cats.effect.Async

import org.tesselation.http.routes.HealthRoutes

import org.http4s.implicits.http4sKleisliResponseSyntaxOptionT
import org.http4s.server.middleware.{RequestLogger, ResponseLogger}
import org.http4s.{HttpApp, HttpRoutes}

object HttpApi {

  def make[F[_]: Async](
    services: Services[F]
  ): HttpApi[F] =
    new HttpApi[F](services) {}
}

sealed abstract class HttpApi[F[_]: Async] private (
  services: Services[F]
) {
  private val healthRoutes = HealthRoutes[F](services.healthCheck).routes

  private val openRoutes: HttpRoutes[F] =
    healthRoutes

  private val routes: HttpRoutes[F] = openRoutes

  private val loggers: HttpApp[F] => HttpApp[F] = {
    { http: HttpApp[F] =>
      RequestLogger.httpApp(true, true)(http)
    }.andThen { http: HttpApp[F] =>
      ResponseLogger.httpApp(true, true)(http)
    }
  }

  val httpApp: HttpApp[F] = loggers(routes.orNotFound)

}
