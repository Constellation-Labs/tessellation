package io.constellationnetwork.currency.l1.modules

import java.security.PrivateKey

import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.semigroupk._

import io.constellationnetwork.currency.dataApplication.dataApplication.DataApplicationCustomRoutes
import io.constellationnetwork.currency.dataApplication.{BaseDataApplicationL1Service, L1NodeContext}
import io.constellationnetwork.currency.l1.http.DataApplicationRoutes
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.dag.l1.http.{Routes => DAGRoutes}
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.config.types.HttpConfig
import io.constellationnetwork.node.shared.http.p2p.middlewares.{PeerAuthMiddleware, `X-Id-Middleware`}
import io.constellationnetwork.node.shared.http.routes._
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.semver.{MetagraphVersion, TessellationVersion}
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import io.constellationnetwork.security.{Hasher, HasherSelector, SecurityProvider}

import org.http4s.implicits.http4sKleisliResponseSyntaxOptionT
import org.http4s.server.middleware.{CORS, RequestLogger, ResponseLogger}
import org.http4s.{HttpApp, HttpRoutes}

object HttpApi {

  def make[
    F[_]: Async: HasherSelector: SecurityProvider: Metrics: Supervisor: L1NodeContext,
    P <: StateProof,
    S <: Snapshot,
    SI <: SnapshotInfo[P],
    R <: CliMethod
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
      CurrencySnapshotInfo,
      R
    ],
    programs: Programs[
      F,
      CurrencySnapshotStateProof,
      CurrencyIncrementalSnapshot,
      CurrencySnapshotInfo,
      R
    ],
    selfId: PeerId,
    nodeVersion: TessellationVersion,
    httpCfg: HttpConfig,
    maybeMetagraphVersion: Option[MetagraphVersion],
    txHasher: Hasher[F]
  ): HttpApi[F, R] =
    new HttpApi[F, R](
      maybeDataApplication,
      storages,
      queues,
      privateKey,
      services,
      programs,
      selfId,
      nodeVersion,
      httpCfg,
      maybeMetagraphVersion,
      txHasher
    ) {}
}

sealed abstract class HttpApi[
  F[_]: Async: HasherSelector: SecurityProvider: Metrics: Supervisor: L1NodeContext,
  R <: CliMethod
] private (
  maybeDataApplication: Option[BaseDataApplicationL1Service[F]],
  storages: Storages[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
  queues: Queues[F],
  privateKey: PrivateKey,
  services: Services[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotInfo, R],
  programs: Programs[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotInfo, R],
  selfId: PeerId,
  nodeVersion: TessellationVersion,
  httpCfg: HttpConfig,
  maybeMetagraphVersion: Option[MetagraphVersion],
  txHasher: Hasher[F]
) {

  private val clusterRoutes =
    HasherSelector[F].withCurrent { implicit hasher =>
      ClusterRoutes[F](programs.joining, programs.peerDiscovery, storages.cluster, services.cluster, services.collateral)
    }
  private val registrationRoutes = HasherSelector[F].withCurrent(implicit hasher => RegistrationRoutes[F](services.cluster))
  private val gossipRoutes = GossipRoutes[F](storages.rumor, services.gossip)
  private val dataApplicationRoutes = maybeDataApplication.map { da =>
    HasherSelector[F].withCurrent { implicit hasher =>
      DataApplicationRoutes[F](
        queues.dataApplicationPeerConsensusInput,
        storages.l0Cluster,
        da,
        queues.dataUpdates,
        storages.lastGlobalSnapshot,
        storages.lastSnapshot
      )
    }
  }
  private val allowSpendRoutes =
    HasherSelector[F].withCurrent { implicit hasher =>
      AllowSpendRoutes[F](
        queues.swapPeerConsensusInput,
        storages.l0Cluster,
        services.allowSpend,
        storages.allowSpend
      )
    }

  private val tokenLockRoutes =
    HasherSelector[F].withCurrent { implicit hasher =>
      TokenLockRoutes[F](
        queues.tokenLockPeerConsensusInput,
        storages.l0Cluster,
        services.tokenLock,
        storages.tokenLock
      )
    }

  private val currencyRoutes =
    DAGRoutes[F](
      services.transaction,
      storages.transaction,
      storages.l0Cluster,
      queues.peerBlockConsensusInput,
      queues.swapPeerConsensusInput,
      queues.tokenLockPeerConsensusInput,
      txHasher
    )
  private val nodeRoutes = NodeRoutes[F](storages.node, storages.session, storages.cluster, nodeVersion, httpCfg, selfId)

  private val metricRoutes = MetricRoutes[F]().publicRoutes
  private val targetRoutes = HasherSelector[F].withCurrent(implicit hasher => TargetRoutes[F](services.cluster).publicRoutes)
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
          dataApplicationRoutes.map(_.publicRoutes).getOrElse {
            currencyRoutes.publicRoutes <+>
              allowSpendRoutes.publicRoutes <+>
              tokenLockRoutes.publicRoutes
          } <+>
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
              dataApplicationRoutes.map(_.p2pRoutes).getOrElse {
                currencyRoutes.p2pRoutes <+>
                  allowSpendRoutes.p2pRoutes <+>
                  tokenLockRoutes.p2pRoutes
              } <+>
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
