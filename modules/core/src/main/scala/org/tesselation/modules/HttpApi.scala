package org.tesselation.modules

import cats.effect.Async
import cats.syntax.semigroupk._

import org.tesselation.http.routes.{ClusterRoutes, HealthRoutes}

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
  private val healthRoutes = HealthRoutes[F](services.healthcheck.healthCheck).routes
  private val clusterRoutes = ClusterRoutes[F](services.clusterStorage, services.cluster)

  private val openRoutes: HttpRoutes[F] =
    healthRoutes

  private val p2pRoutes: HttpRoutes[F] =
    healthRoutes

  private val cliRoutes: HttpRoutes[F] =
    healthRoutes <+> clusterRoutes.cliRoutes

  private val loggers: HttpApp[F] => HttpApp[F] = {
    { http: HttpApp[F] =>
      RequestLogger.httpApp(true, true)(http)
    }.andThen { http: HttpApp[F] =>
      ResponseLogger.httpApp(true, true)(http)
    }
  }

  val publicApp: HttpApp[F] = loggers(openRoutes.orNotFound)
  val p2pApp: HttpApp[F] = loggers(p2pRoutes.orNotFound)
  val cliApp: HttpApp[F] = loggers(cliRoutes.orNotFound)

}
