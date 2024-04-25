package org.tessellation.dag.l1.modules

import java.security.PrivateKey

import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.semigroupk._

import org.tessellation.dag.l1.http.Routes
import org.tessellation.node.shared.config.types.HttpConfig
import org.tessellation.node.shared.http.p2p.middlewares.{PeerAuthMiddleware, `X-Id-Middleware`}
import org.tessellation.node.shared.http.routes._
import org.tessellation.node.shared.infrastructure.metrics.Metrics
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.semver.TessellationVersion
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import org.tessellation.security.{Hasher, HasherSelector, SecurityProvider}

import org.http4s.implicits.http4sKleisliResponseSyntaxOptionT
import org.http4s.server.middleware.{CORS, RequestLogger, ResponseLogger}
import org.http4s.{HttpApp, HttpRoutes}

object HttpApi {

  def make[
    F[_]: Async: HasherSelector: SecurityProvider: Metrics: Supervisor,
    P <: StateProof,
    S <: Snapshot,
    SI <: SnapshotInfo[P]
  ](
    storages: Storages[F, P, S, SI],
    queues: Queues[F],
    privateKey: PrivateKey,
    services: Services[F, P, S, SI],
    programs: Programs[F, P, S, SI],
    selfId: PeerId,
    nodeVersion: TessellationVersion,
    httpCfg: HttpConfig,
    txHasher: Hasher[F]
  ): HttpApi[F, P, S, SI] =
    new HttpApi[F, P, S, SI](storages, queues, privateKey, services, programs, selfId, nodeVersion, httpCfg, txHasher) {}
}

sealed abstract class HttpApi[
  F[_]: Async: HasherSelector: SecurityProvider: Metrics: Supervisor,
  P <: StateProof,
  S <: Snapshot,
  SI <: SnapshotInfo[P]
] private (
  storages: Storages[F, P, S, SI],
  queues: Queues[F],
  privateKey: PrivateKey,
  services: Services[F, P, S, SI],
  programs: Programs[F, P, S, SI],
  selfId: PeerId,
  nodeVersion: TessellationVersion,
  httpCfg: HttpConfig,
  txHasher: Hasher[F]
) {
  private val clusterRoutes =
    HasherSelector[F].withCurrent { implicit hasher =>
      ClusterRoutes[F](programs.joining, programs.peerDiscovery, storages.cluster, services.cluster, services.collateral)
    }
  private val registrationRoutes = RegistrationRoutes[F](services.cluster)
  private val gossipRoutes = GossipRoutes[F](storages.rumor, services.gossip)
  private val dagRoutes =
    Routes[F](services.transaction, storages.transaction, storages.l0Cluster, queues.peerBlockConsensusInput, txHasher)
  private val nodeRoutes = NodeRoutes[F](storages.node, storages.session, storages.cluster, nodeVersion, httpCfg, selfId)

  private val metricRoutes = MetricRoutes[F]().publicRoutes
  private val targetRoutes =
    HasherSelector[F].withCurrent { implicit hasher =>
      TargetRoutes[F](services.cluster).publicRoutes
    }

  private val openRoutes: HttpRoutes[F] =
    CORS.policy.withAllowOriginAll.withAllowHeadersAll.withAllowCredentials(false).apply {
      `X-Id-Middleware`.responseMiddleware(selfId) {
        dagRoutes.publicRoutes <+>
          clusterRoutes.publicRoutes <+>
          nodeRoutes.publicRoutes <+>
          metricRoutes <+>
          targetRoutes
      }
    }

  private val p2pRoutes: HttpRoutes[F] =
    PeerAuthMiddleware.responseSignerMiddleware(privateKey, storages.session, selfId)(
      registrationRoutes.p2pPublicRoutes <+>
        clusterRoutes.p2pPublicRoutes <+>
        PeerAuthMiddleware.requestVerifierMiddleware(
          PeerAuthMiddleware.requestTokenVerifierMiddleware(services.session)(
            clusterRoutes.p2pRoutes <+>
              nodeRoutes.p2pRoutes <+>
              gossipRoutes.p2pRoutes <+>
              dagRoutes.p2pRoutes
          )
        )
    )

  private val cliRoutes: HttpRoutes[F] =
    clusterRoutes.cliRoutes

  private val loggers: HttpApp[F] => HttpApp[F] = { http: HttpApp[F] =>
    RequestLogger.httpApp(logHeaders = true, logBody = false)(http)
  }.andThen { http: HttpApp[F] =>
    ResponseLogger.httpApp(logHeaders = true, logBody = false)(http)
  }

  val publicApp: HttpApp[F] = loggers(openRoutes.orNotFound)
  val p2pApp: HttpApp[F] = loggers(p2pRoutes.orNotFound)
  val cliApp: HttpApp[F] = loggers(cliRoutes.orNotFound)

}
