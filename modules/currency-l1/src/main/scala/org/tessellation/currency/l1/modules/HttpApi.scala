package org.tessellation.currency.l1.modules

import java.security.PrivateKey

import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.semigroupk._

import org.tessellation.currency.dataApplication.dataApplication.DataApplicationCustomRoutes
import org.tessellation.currency.dataApplication.{BaseDataApplicationL1Service, L1NodeContext}
import org.tessellation.currency.l1.http.DataApplicationRoutes
import org.tessellation.currency.schema.currency._
import org.tessellation.dag.l1.http.{Routes => DAGRoutes}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.config.types.HttpConfig
import org.tessellation.node.shared.http.p2p.middlewares.{PeerAuthMiddleware, `X-Id-Middleware`}
import org.tessellation.node.shared.http.routes._
import org.tessellation.node.shared.infrastructure.metrics.Metrics
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.semver.{MetagraphVersion, TessellationVersion}
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import org.tessellation.security.{Hasher, SecurityProvider}

import org.http4s.implicits.http4sKleisliResponseSyntaxOptionT
import org.http4s.server.middleware.{CORS, RequestLogger, ResponseLogger}
import org.http4s.{HttpApp, HttpRoutes}

object HttpApi {

  def make[
    F[_]: Async: KryoSerializer: Hasher: SecurityProvider: Metrics: Supervisor: L1NodeContext,
    P <: StateProof,
    S <: Snapshot,
    SI <: SnapshotInfo[P]
  ](
    maybeDataApplication: Option[BaseDataApplicationL1Service[F]],
    storages: Storages[
      F,
      CurrencySnapshotStateProof,
      CurrencyIncrementalSnapshot,
      CurrencySnapshotInfo
    ],
    queues: Queues[F],
    privateKey: PrivateKey,
    services: Services[
      F,
      CurrencySnapshotStateProof,
      CurrencyIncrementalSnapshot,
      CurrencySnapshotInfo
    ],
    programs: Programs[
      F,
      CurrencySnapshotStateProof,
      CurrencyIncrementalSnapshot,
      CurrencySnapshotInfo
    ],
    selfId: PeerId,
    nodeVersion: TessellationVersion,
    httpCfg: HttpConfig,
    maybeMetagraphVersion: Option[MetagraphVersion]
  ): HttpApi[F] =
    new HttpApi[F](
      maybeDataApplication,
      storages,
      queues,
      privateKey,
      services,
      programs,
      selfId,
      nodeVersion,
      httpCfg,
      maybeMetagraphVersion
    ) {}
}

sealed abstract class HttpApi[
  F[_]: Async: KryoSerializer: Hasher: SecurityProvider: Metrics: Supervisor: L1NodeContext
] private (
  maybeDataApplication: Option[BaseDataApplicationL1Service[F]],
  storages: Storages[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
  queues: Queues[F],
  privateKey: PrivateKey,
  services: Services[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
  programs: Programs[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
  selfId: PeerId,
  nodeVersion: TessellationVersion,
  httpCfg: HttpConfig,
  maybeMetagraphVersion: Option[MetagraphVersion]
) {

  private val clusterRoutes =
    ClusterRoutes[F](programs.joining, programs.peerDiscovery, storages.cluster, services.cluster, services.collateral)
  private val registrationRoutes = RegistrationRoutes[F](services.cluster)
  private val gossipRoutes = GossipRoutes[F](storages.rumor, services.gossip)
  private val routes = maybeDataApplication.map { da =>
    DataApplicationRoutes[F](
      queues.dataApplicationPeerConsensusInput,
      storages.l0Cluster,
      da,
      queues.dataUpdates,
      storages.lastGlobalSnapshot,
      storages.lastSnapshot
    )
  }
  private val currencyRoutes =
    DAGRoutes[F](services.transaction, storages.transaction, storages.l0Cluster, queues.peerBlockConsensusInput)
  private val nodeRoutes = NodeRoutes[F](storages.node, storages.session, storages.cluster, nodeVersion, httpCfg, selfId)

  private val metricRoutes = MetricRoutes[F]().publicRoutes
  private val targetRoutes = TargetRoutes[F](services.cluster).publicRoutes
  private val metagraphNodeRoutes = maybeMetagraphVersion.map { metagraphVersion =>
    MetagraphRoutes[F](storages.node, storages.session, storages.cluster, httpCfg, selfId, nodeVersion, metagraphVersion)
  }

  private val openRoutes: HttpRoutes[F] =
    CORS.policy.withAllowOriginAll.withAllowHeadersAll.withAllowCredentials(false).apply {
      `X-Id-Middleware`.responseMiddleware(selfId) {
        clusterRoutes.publicRoutes <+>
          nodeRoutes.publicRoutes <+>
          metricRoutes <+>
          targetRoutes <+>
          routes.map(_.publicRoutes).getOrElse(currencyRoutes.publicRoutes) <+>
          metagraphNodeRoutes.map(_.publicRoutes).getOrElse(HttpRoutes.empty) <+>
          DataApplicationCustomRoutes.publicRoutes[F, L1NodeContext[F]](maybeDataApplication)
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
              routes.map(_.p2pRoutes).getOrElse(currencyRoutes.p2pRoutes) <+>
              metagraphNodeRoutes.map(_.p2pRoutes).getOrElse(HttpRoutes.empty)
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
