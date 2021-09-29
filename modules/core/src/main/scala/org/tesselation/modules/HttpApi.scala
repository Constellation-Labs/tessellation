package org.tesselation.modules

import cats.effect.Async
import cats.syntax.semigroupk._

import org.tesselation.config.AppEnvironment
import org.tesselation.config.AppEnvironment.Testnet
import org.tesselation.http.routes._

import org.http4s.implicits.http4sKleisliResponseSyntaxOptionT
import org.http4s.server.middleware.{RequestLogger, ResponseLogger}
import org.http4s.{HttpApp, HttpRoutes}

object HttpApi {

  def make[F[_]: Async](
    storages: Storages[F],
    services: Services[F],
    programs: Programs[F],
    environment: AppEnvironment
  ): HttpApi[F] =
    new HttpApi[F](storages, services, programs, environment) {}
}

sealed abstract class HttpApi[F[_]: Async] private (
  storages: Storages[F],
  services: Services[F],
  programs: Programs[F],
  environment: AppEnvironment
) {
  private val healthRoutes = HealthRoutes[F](services.healthcheck).routes
  private val clusterRoutes =
    ClusterRoutes[F](programs.joining, programs.peerDiscovery, storages.cluster)
  private val registrationRoutes = RegistrationRoutes[F](services.cluster)

  private val debugRoutes = DebugRoutes[F](storages, services).routes

  private val openRoutes: HttpRoutes[F] =
    (if (environment == Testnet) debugRoutes else HttpRoutes.empty) <+>
      healthRoutes

  private val p2pRoutes: HttpRoutes[F] =
    healthRoutes <+>
      clusterRoutes.p2pRoutes <+>
      registrationRoutes.p2pRoutes

  private val cliRoutes: HttpRoutes[F] =
    healthRoutes <+>
      clusterRoutes.cliRoutes

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
