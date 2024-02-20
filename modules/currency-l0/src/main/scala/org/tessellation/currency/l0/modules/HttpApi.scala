package org.tessellation.currency.l0.modules

import java.security.PrivateKey

import cats.effect.Async
import cats.syntax.semigroupk._

import org.tessellation.currency.dataApplication.dataApplication.DataApplicationCustomRoutes
import org.tessellation.currency.dataApplication.{BaseDataApplicationL0Service, L0NodeContext}
import org.tessellation.currency.l0.cell.{L0Cell, L0CellInput}
import org.tessellation.currency.l0.http.routes.{CurrencyBlockRoutes, DataBlockRoutes}
import org.tessellation.currency.l0.snapshot.CurrencySnapshotKey
import org.tessellation.currency.l0.snapshot.schema.CurrencyConsensusOutcome
import org.tessellation.currency.schema.currency._
import org.tessellation.env.AppEnvironment
import org.tessellation.env.AppEnvironment.{Dev, Integrationnet, Testnet}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.config.types.HttpConfig
import org.tessellation.node.shared.http.p2p.middlewares.{PeerAuthMiddleware, `X-Id-Middleware`}
import org.tessellation.node.shared.http.routes._
import org.tessellation.node.shared.infrastructure.metrics.Metrics
import org.tessellation.node.shared.snapshot.currency.CurrencySnapshotEvent
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.semver.{MetagraphVersion, TessellationVersion}
import org.tessellation.security.{Hasher, SecurityProvider}

import eu.timepit.refined.auto._
import org.http4s.implicits.http4sKleisliResponseSyntaxOptionT
import org.http4s.server.middleware.{CORS, RequestLogger, ResponseLogger}
import org.http4s.{HttpApp, HttpRoutes}

object HttpApi {

  def make[F[_]: Async: SecurityProvider: KryoSerializer: Hasher: Metrics: L0NodeContext](
    storages: Storages[F],
    queues: Queues[F],
    services: Services[F],
    programs: Programs[F],
    privateKey: PrivateKey,
    environment: AppEnvironment,
    selfId: PeerId,
    nodeVersion: TessellationVersion,
    httpCfg: HttpConfig,
    maybeDataApplication: Option[BaseDataApplicationL0Service[F]],
    maybeMetagraphVersion: Option[MetagraphVersion]
  ): HttpApi[F] =
    new HttpApi[F](
      storages,
      queues,
      services,
      programs,
      privateKey,
      environment,
      selfId,
      nodeVersion,
      httpCfg,
      maybeDataApplication,
      maybeMetagraphVersion
    ) {}
}

sealed abstract class HttpApi[F[_]: Async: SecurityProvider: KryoSerializer: Hasher: Metrics: L0NodeContext] private (
  storages: Storages[F],
  queues: Queues[F],
  services: Services[F],
  programs: Programs[F],
  privateKey: PrivateKey,
  environment: AppEnvironment,
  selfId: PeerId,
  nodeVersion: TessellationVersion,
  httpCfg: HttpConfig,
  maybeDataApplication: Option[BaseDataApplicationL0Service[F]],
  maybeMetagraphVersion: Option[MetagraphVersion]
) {

  private val mkCell = (event: CurrencySnapshotEvent) => L0Cell.mkL0Cell(queues.l1Output).apply(L0CellInput.HandleL1Block(event))

  private val snapshotRoutes = SnapshotRoutes[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](
    storages.snapshot,
    None,
    "/snapshots",
    storages.node
  )
  private val clusterRoutes =
    ClusterRoutes[F](programs.joining, programs.peerDiscovery, storages.cluster, services.cluster, services.collateral)
  private val nodeRoutes = NodeRoutes[F](storages.node, storages.session, storages.cluster, nodeVersion, httpCfg, selfId)

  private val registrationRoutes = RegistrationRoutes[F](services.cluster)
  private val gossipRoutes = GossipRoutes[F](storages.rumor, services.gossip)
  private val currencyBlockRoutes = CurrencyBlockRoutes[F](mkCell)
  private val dataBlockRoutes = maybeDataApplication.map { da =>
    implicit val (d, e) = (da.dataDecoder, da.calculatedStateEncoder)
    DataBlockRoutes[F](mkCell, da)
  }
  private val metagraphNodeRoutes = maybeMetagraphVersion.map { metagraphVersion =>
    MetagraphRoutes[F](storages.node, storages.session, storages.cluster, httpCfg, selfId, nodeVersion, metagraphVersion)
  }

  private val walletRoutes = WalletRoutes[F, CurrencyIncrementalSnapshot]("/currency", services.address)

  private val consensusInfoRoutes =
    new ConsensusInfoRoutes[F, CurrencySnapshotKey, CurrencyConsensusOutcome](services.cluster, services.consensus.storage, selfId)
  private val consensusRoutes = services.consensus.routes.p2pRoutes

  private val debugRoutes = DebugRoutes[F](
    storages.cluster,
    services.consensus,
    services.gossip,
    services.session
  ).publicRoutes

  private val metricRoutes = MetricRoutes[F]().publicRoutes
  private val targetRoutes = TargetRoutes[F](services.cluster).publicRoutes

  private val currencyMessageRoutes = new CurrencyMessageRoutes[F](services.currencyMessageService).publicRoutes

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
              DataApplicationCustomRoutes.publicRoutes[F, L0NodeContext[F]](maybeDataApplication)
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
