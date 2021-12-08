package org.tessellation.modules

import cats.effect.Async
import cats.syntax.semigroupk._

import org.tessellation.config.AppEnvironment
import org.tessellation.config.AppEnvironment.{Dev, Testnet}
import org.tessellation.http.routes
import org.tessellation.http.routes._
import org.tessellation.kryo.KryoSerializer

import org.http4s.implicits.http4sKleisliResponseSyntaxOptionT
import org.http4s.server.middleware.{RequestLogger, ResponseLogger}
import org.http4s.{HttpApp, HttpRoutes}

object HttpApi {

  def make[F[_]: Async: KryoSerializer](
    storages: Storages[F],
    queues: Queues[F],
    services: Services[F],
    programs: Programs[F],
    environment: AppEnvironment,
    stateChannelp2pRoutes: HttpRoutes[F]
  ): HttpApi[F] =
    new HttpApi[F](storages, queues, services, programs, environment, stateChannelp2pRoutes) {}
}

sealed abstract class HttpApi[F[_]: Async: KryoSerializer] private (
  storages: Storages[F],
  queues: Queues[F],
  services: Services[F],
  programs: Programs[F],
  environment: AppEnvironment,
  stateChannelp2pRoutes: HttpRoutes[F]
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
    (if (environment == Testnet || environment == Dev) debugRoutes else HttpRoutes.empty) <+>
      healthRoutes <+> metricRoutes <+> stateChannelRoutes.publicRoutes

  private val p2pRoutes: HttpRoutes[F] =
    healthRoutes <+>
      clusterRoutes.p2pRoutes <+>
      registrationRoutes.p2pRoutes <+>
      gossipRoutes.p2pRoutes <+>
      trustRoutes.p2pRoutes <+>
      stateChannelp2pRoutes

  private val cliRoutes: HttpRoutes[F] =
    healthRoutes <+>
      clusterRoutes.cliRoutes

  private val loggers: HttpApp[F] => HttpApp[F] = {
    { http: HttpApp[F] =>
      RequestLogger.httpApp(logHeaders = true, logBody = false)(http)
    }.andThen { http: HttpApp[F] =>
      ResponseLogger.httpApp(logHeaders = true, logBody = false)(http)
    }
  }

  val publicApp: HttpApp[F] = loggers(openRoutes.orNotFound)
  val p2pApp: HttpApp[F] = loggers(p2pRoutes.orNotFound)
  val cliApp: HttpApp[F] = loggers(cliRoutes.orNotFound)

}
