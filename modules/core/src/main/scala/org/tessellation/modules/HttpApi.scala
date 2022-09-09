package org.tessellation.modules

import java.security.PrivateKey

import cats.effect.Async
import cats.syntax.semigroupk._

import org.tessellation.dag.snapshot.SnapshotOrdinal
import org.tessellation.domain.cell.L0Cell
import org.tessellation.http.routes._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.config.AppEnvironment.{Dev, Testnet}
import org.tessellation.sdk.http.p2p.middleware.{PeerAuthMiddleware, `X-Id-Middleware`}
import org.tessellation.sdk.http.routes
import org.tessellation.sdk.http.routes._
import org.tessellation.sdk.infrastructure.healthcheck.declaration.PeerDeclarationHealthcheckRoutes
import org.tessellation.sdk.infrastructure.healthcheck.ping.PingHealthCheckRoutes
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.security.SecurityProvider

import org.http4s.implicits.http4sKleisliResponseSyntaxOptionT
import org.http4s.server.Router
import org.http4s.server.middleware.{CORS, RequestLogger, ResponseLogger}
import org.http4s.{HttpApp, HttpRoutes}

object HttpApi {

  def make[F[_]: Async: SecurityProvider: KryoSerializer: Metrics](
    storages: Storages[F],
    queues: Queues[F],
    services: Services[F],
    programs: Programs[F],
    healthchecks: HealthChecks[F],
    privateKey: PrivateKey,
    environment: AppEnvironment,
    selfId: PeerId
  ): HttpApi[F] =
    new HttpApi[F](storages, queues, services, programs, healthchecks, privateKey, environment, selfId) {}
}

sealed abstract class HttpApi[F[_]: Async: SecurityProvider: KryoSerializer: Metrics] private (
  storages: Storages[F],
  queues: Queues[F],
  services: Services[F],
  programs: Programs[F],
  healthchecks: HealthChecks[F],
  privateKey: PrivateKey,
  environment: AppEnvironment,
  selfId: PeerId
) {

  private val mkDagCell: L0Cell.Mk[F] = L0Cell.mkL0Cell(queues.l1Output, queues.stateChannelOutput)

  private val clusterRoutes =
    ClusterRoutes[F](programs.joining, programs.peerDiscovery, storages.cluster, services.cluster, services.collateral)
  private val nodeRoutes = NodeRoutes[F](storages.node)
  private val registrationRoutes = RegistrationRoutes[F](services.cluster)
  private val gossipRoutes = GossipRoutes[F](storages.rumor, services.gossip)
  private val trustRoutes = TrustRoutes[F](storages.trust, programs.trustPush)
  private val stateChannelRoutes = StateChannelRoutes[F](mkDagCell)
  private val globalSnapshotRoutes = GlobalSnapshotRoutes[F](storages.globalSnapshot)
  private val dagRoutes = DagRoutes[F](services.dag, mkDagCell)

  private val healthcheckP2PRoutes = {
    val pingHealthcheckRoutes = PingHealthCheckRoutes[F](healthchecks.ping)
    val peerDeclaration = PeerDeclarationHealthcheckRoutes[F, SnapshotOrdinal](services.consensus.healthcheck)

    Router("healthcheck" -> (pingHealthcheckRoutes.p2pRoutes <+> peerDeclaration.p2pRoutes))
  }

  private val debugRoutes = DebugRoutes[F](storages, services).routes

  private val metricRoutes = routes.MetricRoutes[F]().routes
  private val targetRoutes = routes.TargetRoutes[F](services.cluster).routes

  private val openRoutes: HttpRoutes[F] =
    CORS.policy.withAllowOriginAll.withAllowHeadersAll.withAllowCredentials(false).apply {
      PeerAuthMiddleware
        .responseSignerMiddleware(privateKey, storages.session, selfId) {
          `X-Id-Middleware`.responseMiddleware(selfId) {
            (if (environment == Testnet || environment == Dev) debugRoutes else HttpRoutes.empty) <+>
              metricRoutes <+>
              targetRoutes <+>
              stateChannelRoutes.publicRoutes <+>
              clusterRoutes.publicRoutes <+>
              globalSnapshotRoutes.publicRoutes <+>
              dagRoutes.publicRoutes <+>
              nodeRoutes.publicRoutes
          }
        }
    }

  private val p2pRoutes: HttpRoutes[F] =
    PeerAuthMiddleware.responseSignerMiddleware(privateKey, storages.session, selfId)(
      registrationRoutes.p2pPublicRoutes <+>
        clusterRoutes.p2pPublicRoutes <+>
        PeerAuthMiddleware.requestVerifierMiddleware(
          PeerAuthMiddleware.requestTokenVerifierMiddleware(services.session)(
            PeerAuthMiddleware.requestCollateralVerifierMiddleware(services.collateral)(
              clusterRoutes.p2pRoutes <+>
                nodeRoutes.p2pRoutes <+>
                gossipRoutes.p2pRoutes <+>
                trustRoutes.p2pRoutes <+>
                globalSnapshotRoutes.p2pRoutes <+>
                healthcheckP2PRoutes <+>
                services.consensus.p2pRoutes
            )
          )
        )
    )

  private val cliRoutes: HttpRoutes[F] =
    clusterRoutes.cliRoutes <+>
      trustRoutes.cliRoutes

  private val loggers: HttpApp[F] => HttpApp[F] = { http: HttpApp[F] =>
    RequestLogger.httpApp(logHeaders = true, logBody = false)(http)
  }.andThen { http: HttpApp[F] =>
    ResponseLogger.httpApp(logHeaders = true, logBody = false)(http)
  }

  val publicApp: HttpApp[F] = loggers(openRoutes.orNotFound)
  val p2pApp: HttpApp[F] = loggers(p2pRoutes.orNotFound)
  val cliApp: HttpApp[F] = loggers(cliRoutes.orNotFound)

}
