package org.tesselation.modules

import cats.effect.Async
import cats.syntax.semigroupk._

import org.tesselation.config.AppEnvironment
import org.tesselation.config.AppEnvironment.Testnet
import org.tesselation.http.routes
import org.tesselation.http.routes._
import org.tesselation.kryo.KryoSerializer

import org.http4s.implicits.http4sKleisliResponseSyntaxOptionT
import org.http4s.server.middleware.{RequestLogger, ResponseLogger}
import org.http4s.{HttpApp, HttpRoutes}

object HttpApi {

  def make[F[_]: Async: KryoSerializer](
    storages: Storages[F],
    queues: Queues[F],
    services: Services[F],
    programs: Programs[F],
    environment: AppEnvironment
  ): HttpApi[F] =
    new HttpApi[F](storages, queues, services, programs, environment) {}
}

sealed abstract class HttpApi[F[_]: Async: KryoSerializer] private (
  storages: Storages[F],
  queues: Queues[F],
  services: Services[F],
  programs: Programs[F],
  environment: AppEnvironment
) {
  private val healthRoutes = HealthRoutes[F](services.healthcheck).routes
  private val clusterRoutes =
    ClusterRoutes[F](programs.joining, programs.peerDiscovery, programs.trustPush, storages.cluster, storages.trust)
  private val registrationRoutes = RegistrationRoutes[F](services.cluster)
  private val gossipRoutes = routes.GossipRoutes[F](storages.rumor, queues.rumor, services.gossip)
  private val trustRoutes = routes.TrustRoutes[F](storages.trust)
  private val stateChannelRoutes = routes.StateChannelRoutes[F](services.stateChannelRouter)

  private val debugRoutes = DebugRoutes[F](storages, services).routes

  private val metricRoutes = MetricRoutes[F](services).routes

  private val openRoutes: HttpRoutes[F] =
    (if (environment == Testnet) debugRoutes else HttpRoutes.empty) <+>
      healthRoutes <+> metricRoutes <+> stateChannelRoutes.publicRoutes

  private val p2pRoutes: HttpRoutes[F] =
    healthRoutes <+>
      clusterRoutes.p2pRoutes <+>
      registrationRoutes.p2pRoutes <+>
      gossipRoutes.p2pRoutes <+>
      trustRoutes.p2pRoutes

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
