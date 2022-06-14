package org.tessellation.dag.l1.modules

import java.security.PrivateKey

import cats.effect.Async
import cats.syntax.semigroupk._

import org.tessellation.dag.l1.http.Routes
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.http.p2p.middleware.{PeerAuthMiddleware, `X-Id-Middleware`}
import org.tessellation.sdk.http.routes._
import org.tessellation.sdk.infrastructure.healthcheck.ping.PingHealthCheckRoutes
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.security.SecurityProvider

import org.http4s.implicits.http4sKleisliResponseSyntaxOptionT
import org.http4s.server.Router
import org.http4s.server.middleware.{RequestLogger, ResponseLogger}
import org.http4s.{HttpApp, HttpRoutes}

object HttpApi {

  def make[F[_]: Async: KryoSerializer: SecurityProvider: Metrics](
    storages: Storages[F],
    queues: Queues[F],
    privateKey: PrivateKey,
    services: Services[F],
    programs: Programs[F],
    healthchecks: HealthChecks[F],
    selfId: PeerId
  ): HttpApi[F] =
    new HttpApi[F](storages, queues, privateKey, services, programs, healthchecks, selfId) {}
}

sealed abstract class HttpApi[F[_]: Async: KryoSerializer: SecurityProvider: Metrics] private (
  storages: Storages[F],
  queues: Queues[F],
  privateKey: PrivateKey,
  services: Services[F],
  programs: Programs[F],
  healthchecks: HealthChecks[F],
  selfId: PeerId
) {
  private val clusterRoutes =
    ClusterRoutes[F](programs.joining, programs.peerDiscovery, storages.cluster, services.cluster, services.collateral)
  private val registrationRoutes = RegistrationRoutes[F](services.cluster)
  private val gossipRoutes = GossipRoutes[F](storages.rumor, queues.rumor, services.gossip)
  private val dagRoutes = Routes[F](services.transaction, storages.transaction, queues.peerBlockConsensusInput)
  private val nodeRoutes = NodeRoutes[F](storages.node)
  private val healthcheckP2PRoutes = {
    val pingHealthcheckRoutes = PingHealthCheckRoutes[F](healthchecks.ping)

    Router("healthcheck" -> pingHealthcheckRoutes.p2pRoutes)
  }

  private val metricRoutes = MetricRoutes[F]().routes
  private val targetRoutes = TargetRoutes[F](services.cluster).routes

  private val openRoutes: HttpRoutes[F] =
    `X-Id-Middleware`.responseMiddleware(selfId) {
      dagRoutes.publicRoutes <+>
        clusterRoutes.publicRoutes <+>
        nodeRoutes.publicRoutes <+>
        metricRoutes <+>
        targetRoutes
    }

  private val p2pRoutes: HttpRoutes[F] =
    PeerAuthMiddleware.responseSignerMiddleware(privateKey, storages.session, selfId)(
      registrationRoutes.p2pPublicRoutes <+>
        clusterRoutes.p2pPublicRoutes <+>
        PeerAuthMiddleware.requestVerifierMiddleware(
          PeerAuthMiddleware.requestTokenVerifierMiddleware(services.session)(
            clusterRoutes.p2pRoutes <+>
              gossipRoutes.p2pRoutes <+>
              dagRoutes.p2pRoutes <+>
              healthcheckP2PRoutes
          )
        )
    )

  private val cliRoutes: HttpRoutes[F] =
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
