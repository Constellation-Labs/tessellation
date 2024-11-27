package io.constellationnetwork.currency.l0.modules

import java.security.PrivateKey

import cats.effect.Async
import cats.syntax.semigroupk._

import io.constellationnetwork.currency.dataApplication.dataApplication.DataApplicationCustomRoutes
import io.constellationnetwork.currency.dataApplication.{BaseDataApplicationL0Service, L0NodeContext}
import io.constellationnetwork.currency.l0.cli.method.Run
import io.constellationnetwork.currency.l0.http.routes._
import io.constellationnetwork.currency.l0.snapshot.CurrencySnapshotKey
import io.constellationnetwork.currency.l0.snapshot.schema.CurrencyConsensusOutcome
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.env.AppEnvironment.{Dev, Integrationnet, Testnet}
import io.constellationnetwork.kernel._
import io.constellationnetwork.node.shared.config.types.HttpConfig
import io.constellationnetwork.node.shared.http.p2p.middlewares.{PeerAuthMiddleware, `X-Id-Middleware`}
import io.constellationnetwork.node.shared.http.routes._
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotEvent
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.semver.{MetagraphVersion, TessellationVersion}
import io.constellationnetwork.security.{HasherSelector, SecurityProvider}

import eu.timepit.refined.auto._
import org.http4s.implicits.http4sKleisliResponseSyntaxOptionT
import org.http4s.server.middleware.{CORS, RequestLogger, ResponseLogger}
import org.http4s.{HttpApp, HttpRoutes}

object HttpApi {

  def make[F[_]: Async: SecurityProvider: HasherSelector: Metrics: L0NodeContext](
    validators: Validators[F],
    storages: Storages[F],
    services: Services[F, Run],
    programs: Programs[F],
    privateKey: PrivateKey,
    environment: AppEnvironment,
    selfId: PeerId,
    nodeVersion: TessellationVersion,
    httpCfg: HttpConfig,
    mkCell: CurrencySnapshotEvent => Cell[F, StackF, _, Either[CellError, Ω], _],
    maybeDataApplication: Option[BaseDataApplicationL0Service[F]],
    maybeMetagraphVersion: Option[MetagraphVersion],
    queues: Queues[F]
  ): HttpApi[F] =
    new HttpApi[F](
      validators,
      storages,
      services,
      programs,
      privateKey,
      environment,
      selfId,
      nodeVersion,
      httpCfg,
      mkCell,
      maybeDataApplication,
      maybeMetagraphVersion,
      queues
    ) {}
}

sealed abstract class HttpApi[F[_]: Async: SecurityProvider: HasherSelector: Metrics: L0NodeContext] private (
  validators: Validators[F],
  storages: Storages[F],
  services: Services[F, Run],
  programs: Programs[F],
  privateKey: PrivateKey,
  environment: AppEnvironment,
  selfId: PeerId,
  nodeVersion: TessellationVersion,
  httpCfg: HttpConfig,
  mkCell: CurrencySnapshotEvent => Cell[F, StackF, _, Either[CellError, Ω], _],
  maybeDataApplication: Option[BaseDataApplicationL0Service[F]],
  maybeMetagraphVersion: Option[MetagraphVersion],
  queues: Queues[F]
) {

  private val snapshotRoutes = SnapshotRoutes[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](
    storages.snapshot,
    None,
    "/snapshots",
    storages.node,
    HasherSelector.alwaysCurrent[F]
  )
  private val clusterRoutes =
    HasherSelector[F].withCurrent { implicit hasher =>
      ClusterRoutes[F](programs.joining, programs.peerDiscovery, storages.cluster, services.cluster, services.collateral)
    }
  private val nodeRoutes = NodeRoutes[F](storages.node, storages.session, storages.cluster, nodeVersion, httpCfg, selfId)

  private val registrationRoutes = RegistrationRoutes[F](services.cluster)
  private val gossipRoutes = GossipRoutes[F](storages.rumor, services.gossip)
  private val currencyBlockRoutes = CurrencyBlockRoutes[F](mkCell)
  private val allowSpendBlockRoutes = AllowSpendBlockRoutes[F](queues.l1Output)
  private val dataBlockRoutes = maybeDataApplication.map { da =>
    implicit val (d, e) = (da.dataDecoder, da.calculatedStateEncoder)
    DataBlockRoutes[F](mkCell, da)
  }
  private val metagraphNodeRoutes = maybeMetagraphVersion.map { metagraphVersion =>
    MetagraphRoutes[F](storages.node, storages.session, storages.cluster, httpCfg, selfId, nodeVersion, metagraphVersion)
  }

  private val walletRoutes = WalletRoutes[F, CurrencyIncrementalSnapshot]("/currency", services.address)

  private val consensusInfoRoutes =
    HasherSelector[F].withCurrent { implicit hasher =>
      new ConsensusInfoRoutes[F, CurrencySnapshotKey, CurrencyConsensusOutcome](services.cluster, services.consensus.storage, selfId)
    }
  private val consensusRoutes = services.consensus.routes.p2pRoutes

  private val debugRoutes = DebugRoutes[F](
    storages.cluster,
    services.consensus,
    services.gossip,
    services.session
  ).publicRoutes

  private val metricRoutes = MetricRoutes[F]().publicRoutes
  private val targetRoutes = HasherSelector[F].withCurrent(implicit hasher => TargetRoutes[F](services.cluster).publicRoutes)

  private val currencyMessageRoutes = HasherSelector[F].withCurrent(implicit hasher =>
    new CurrencyMessageRoutes[F](
      mkCell,
      validators.currencyMessageValidator,
      storages.snapshot,
      storages.identifier,
      storages.lastGlobalSnapshot
    ).publicRoutes
  )

  private val openRoutes: HttpRoutes[F] =
    CORS.policy.withAllowOriginAll.withAllowHeadersAll.withAllowCredentials(false).apply {
      PeerAuthMiddleware
        .responseSignerMiddleware(privateKey, storages.session, selfId) {
          `X-Id-Middleware`.responseMiddleware(selfId) {
            (if (Seq(Dev, Integrationnet, Testnet).contains(environment)) debugRoutes else HttpRoutes.empty) <+>
              metricRoutes <+>
              targetRoutes <+>
              snapshotRoutes.publicRoutes <+>
              clusterRoutes.publicRoutes <+>
              currencyBlockRoutes.publicRoutes <+>
              dataBlockRoutes.map(_.publicRoutes).getOrElse(HttpRoutes.empty) <+>
              walletRoutes.publicRoutes <+>
              nodeRoutes.publicRoutes <+>
              consensusInfoRoutes.publicRoutes <+>
              metagraphNodeRoutes.map(_.publicRoutes).getOrElse(HttpRoutes.empty) <+>
              currencyMessageRoutes <+>
              DataApplicationCustomRoutes.publicRoutes[F, L0NodeContext[F]](maybeDataApplication) <+>
              allowSpendBlockRoutes.publicRoutes
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
              snapshotRoutes.p2pRoutes <+>
                clusterRoutes.p2pRoutes <+>
                nodeRoutes.p2pRoutes <+>
                gossipRoutes.p2pRoutes <+>
                consensusRoutes <+>
                dataBlockRoutes.map(_.p2pRoutes).getOrElse(HttpRoutes.empty) <+>
                metagraphNodeRoutes.map(_.p2pRoutes).getOrElse(HttpRoutes.empty)
            )
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
