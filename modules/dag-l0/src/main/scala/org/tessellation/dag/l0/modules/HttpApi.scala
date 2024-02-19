package org.tessellation.dag.l0.modules

import java.security.PrivateKey

import cats.effect.Async
import cats.syntax.option._
import cats.syntax.semigroupk._

import org.tessellation.dag.l0.domain.cell.{L0Cell, L0CellInput}
import org.tessellation.dag.l0.http.routes._
import org.tessellation.env.AppEnvironment
import org.tessellation.env.AppEnvironment._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.config.types.HttpConfig
import org.tessellation.node.shared.http.p2p.middlewares.{PeerAuthMiddleware, `X-Id-Middleware`}
import org.tessellation.node.shared.http.routes._
import org.tessellation.node.shared.infrastructure.healthcheck.ping.PingHealthCheckRoutes
import org.tessellation.node.shared.infrastructure.metrics.Metrics
import org.tessellation.schema._
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.semver.TessellationVersion
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hasher, SecurityProvider}

import eu.timepit.refined.auto._
import org.http4s.implicits.http4sKleisliResponseSyntaxOptionT
import org.http4s.server.Router
import org.http4s.server.middleware.{CORS, RequestLogger, ResponseLogger}
import org.http4s.{HttpApp, HttpRoutes}

object HttpApi {

  def make[F[_]: Async: SecurityProvider: KryoSerializer: Hasher: Metrics](
    storages: Storages[F],
    queues: Queues[F],
    services: Services[F],
    programs: Programs[F],
    healthchecks: HealthChecks[F],
    privateKey: PrivateKey,
    environment: AppEnvironment,
    selfId: PeerId,
    nodeVersion: TessellationVersion,
    httpCfg: HttpConfig
  ): HttpApi[F] =
    new HttpApi[F](
      storages,
      queues,
      services,
      programs,
      healthchecks,
      privateKey,
      environment,
      selfId,
      nodeVersion,
      httpCfg
    ) {}
}

sealed abstract class HttpApi[F[_]: Async: SecurityProvider: KryoSerializer: Hasher: Metrics] private (
  storages: Storages[F],
  queues: Queues[F],
  services: Services[F],
  programs: Programs[F],
  healthchecks: HealthChecks[F],
  privateKey: PrivateKey,
  environment: AppEnvironment,
  selfId: PeerId,
  nodeVersion: TessellationVersion,
  httpCfg: HttpConfig
) {

  private val mkDagCell = (block: Signed[Block]) =>
    L0Cell.mkL0Cell(queues.l1Output, queues.stateChannelOutput).apply(L0CellInput.HandleDAGL1(block))

  private val clusterRoutes =
    ClusterRoutes[F](programs.joining, programs.peerDiscovery, storages.cluster, services.cluster, services.collateral)
  private val nodeRoutes = NodeRoutes[F](storages.node, storages.session, storages.cluster, nodeVersion, httpCfg, selfId)

  private val registrationRoutes = RegistrationRoutes[F](services.cluster)
  private val gossipRoutes = GossipRoutes[F](storages.rumor, services.gossip)
  private val trustRoutes = TrustRoutes[F](storages.trust, programs.trustPush)
  private val stateChannelRoutes = StateChannelRoutes[F](services.stateChannel)
  private val snapshotRoutes =
    SnapshotRoutes[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
      storages.globalSnapshot,
      storages.fullGlobalSnapshot.some,
      "/global-snapshots",
      storages.node
    )
  private val dagRoutes = DAGBlockRoutes[F](mkDagCell)
  private val walletRoutes = WalletRoutes[F, GlobalIncrementalSnapshot]("/dag", services.address)
  private val consensusInfoRoutes = new ConsensusInfoRoutes[F, SnapshotOrdinal](services.cluster, services.consensus.storage, selfId)
  private val consensusRoutes = services.consensus.routes.p2pRoutes

  private val healthcheckP2PRoutes = {
    val pingHealthcheckRoutes = PingHealthCheckRoutes[F](healthchecks.ping)

    Router("healthcheck" -> pingHealthcheckRoutes.p2pRoutes)
  }

  private val debugRoutes = DebugRoutes[F](
    storages.cluster,
    services.consensus,
    services.gossip,
    services.session,
    DebugTrustRoutes[F](storages.trust).public
  ).publicRoutes

  private val metricRoutes = MetricRoutes[F]().publicRoutes
  private val targetRoutes = TargetRoutes[F](services.cluster).publicRoutes

  private val openRoutes: HttpRoutes[F] =
    CORS.policy.withAllowOriginAll.withAllowHeadersAll.withAllowCredentials(false).apply {
      PeerAuthMiddleware
        .responseSignerMiddleware(privateKey, storages.session, selfId) {
          `X-Id-Middleware`.responseMiddleware(selfId) {
            (if (Seq(Dev, Integrationnet, Testnet).contains(environment)) debugRoutes else HttpRoutes.empty) <+>
              metricRoutes <+>
              targetRoutes <+>
              stateChannelRoutes.publicRoutes <+>
              clusterRoutes.publicRoutes <+>
              snapshotRoutes.publicRoutes <+>
              dagRoutes.publicRoutes <+>
              walletRoutes.publicRoutes <+>
              nodeRoutes.publicRoutes <+>
              consensusInfoRoutes.publicRoutes <+>
              trustRoutes.publicRoutes
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
                snapshotRoutes.p2pRoutes <+>
                healthcheckP2PRoutes <+>
                consensusRoutes
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
