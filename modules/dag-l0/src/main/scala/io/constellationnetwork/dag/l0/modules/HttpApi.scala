package io.constellationnetwork.dag.l0.modules

import java.security.PrivateKey

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.option._
import cats.syntax.semigroupk._

import io.constellationnetwork.dag.l0.domain.cell.{L0Cell, L0CellInput}
import io.constellationnetwork.dag.l0.domain.delegatedStake.DelegatedStakeOutput
import io.constellationnetwork.dag.l0.domain.nodeCollateral.NodeCollateralOutput
import io.constellationnetwork.dag.l0.http.routes._
import io.constellationnetwork.dag.l0.infrastructure.snapshot.GlobalSnapshotKey
import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema.GlobalConsensusOutcome
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.env.AppEnvironment._
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.config.types.{DelegatedStakingConfig, HttpConfig}
import io.constellationnetwork.node.shared.http.p2p.middlewares.{MetricsMiddleware, PeerAuthMiddleware, `X-Id-Middleware`}
import io.constellationnetwork.node.shared.http.routes._
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.modules.SharedValidators
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.node.UpdateNodeParameters
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.semver.TessellationVersion
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{HasherSelector, SecurityProvider}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import org.http4s.implicits.http4sKleisliResponseSyntaxOptionT
import org.http4s.server.middleware.{CORS, RequestLogger, ResponseLogger}
import org.http4s.{HttpApp, HttpRoutes}

object HttpApi {

  def make[F[_]: Async: SecurityProvider: HasherSelector: Metrics, R <: CliMethod](
    storages: Storages[F],
    queues: Queues[F],
    services: Services[F, R],
    programs: Programs[F],
    privateKey: PrivateKey,
    environment: AppEnvironment,
    selfId: PeerId,
    nodeVersion: TessellationVersion,
    httpCfg: HttpConfig,
    sharedValidators: SharedValidators[F],
    delegatedStakingWithdrawalTimeLimit: EpochProgress
  ): HttpApi[F, R] =
    new HttpApi[F, R](
      storages,
      queues,
      services,
      programs,
      privateKey,
      environment,
      selfId,
      nodeVersion,
      httpCfg,
      sharedValidators,
      delegatedStakingWithdrawalTimeLimit
    ) {}
}

sealed abstract class HttpApi[F[_]: Async: SecurityProvider: HasherSelector: Metrics, R <: CliMethod] private (
  storages: Storages[F],
  queues: Queues[F],
  services: Services[F, R],
  programs: Programs[F],
  privateKey: PrivateKey,
  environment: AppEnvironment,
  selfId: PeerId,
  nodeVersion: TessellationVersion,
  httpCfg: HttpConfig,
  sharedValidators: SharedValidators[F],
  delegatedStakingWithdrawalTimeLimit: EpochProgress
) {

  private val mkDagCell = (block: Signed[Block]) =>
    L0Cell
      .mkL0Cell(
        queues.l1Output,
        queues.stateChannelOutput,
        queues.updateNodeParametersOutput,
        queues.delegatedStakeOutput,
        queues.nodeCollateralOutput
      )
      .apply(L0CellInput.HandleDAGL1(block))

  private val mkNodeParametersCell = (params: Signed[UpdateNodeParameters]) =>
    L0Cell
      .mkL0Cell(
        queues.l1Output,
        queues.stateChannelOutput,
        queues.updateNodeParametersOutput,
        queues.delegatedStakeOutput,
        queues.nodeCollateralOutput
      )
      .apply(L0CellInput.HandleUpdateNodeParameters(params))

  private val mkDelegatedStakesCell = (data: DelegatedStakeOutput) =>
    L0Cell
      .mkL0Cell(
        queues.l1Output,
        queues.stateChannelOutput,
        queues.updateNodeParametersOutput,
        queues.delegatedStakeOutput,
        queues.nodeCollateralOutput
      )
      .apply(L0CellInput.HandleDelegatedStake(data))

  private val mkNodeCollateralCell = (data: NodeCollateralOutput) =>
    L0Cell
      .mkL0Cell(
        queues.l1Output,
        queues.stateChannelOutput,
        queues.updateNodeParametersOutput,
        queues.delegatedStakeOutput,
        queues.nodeCollateralOutput
      )
      .apply(L0CellInput.HandleNodeCollateral(data))

  private val clusterRoutes =
    HasherSelector[F].withCurrent { implicit hasher =>
      ClusterRoutes[F](programs.joining, programs.peerDiscovery, storages.cluster, services.cluster, services.collateral)
    }
  private val nodeRoutes = NodeRoutes[F](storages.node, storages.session, storages.cluster, nodeVersion, httpCfg, selfId)

  private val registrationRoutes = RegistrationRoutes[F](services.cluster)
  private val gossipRoutes = GossipRoutes[F](storages.rumor, services.gossip)
  private val trustRoutes = TrustRoutes[F](storages.trust, programs.trustPush)
  private val stateChannelRoutes =
    HasherSelector[F].withCurrent { implicit hasher =>
      StateChannelRoutes[F](services.stateChannel, storages.globalSnapshot)
    }
  private val snapshotRoutes =
    SnapshotRoutes[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
      storages.globalSnapshot,
      storages.fullGlobalSnapshot.some,
      "/global-snapshots",
      storages.node,
      HasherSelector[F]
    )
  private val dagRoutes = DAGBlockRoutes[F](mkDagCell)
  private val allowSpendRoutes = AllowSpendBlockRoutes[F](queues.l1AllowSpendOutput)
  private val tokenLockRoutes = TokenLockBlockRoutes[F](queues.l1TokenLockOutput)
  private val nodeParametersRoutes = HasherSelector[F].withCurrent { implicit hasher =>
    NodeParametersRoutes[F](
      mkNodeParametersCell,
      storages.globalSnapshot,
      storages.node,
      services.cluster,
      sharedValidators.updateNodeParametersValidator
    )
  }
  private val delegatedStakesRoutes =
    HasherSelector[F].withCurrent { implicit hasher =>
      DelegatedStakesRoutes[F](
        mkDelegatedStakesCell,
        sharedValidators.updateDelegatedStakeValidator,
        storages.globalSnapshot,
        storages.node,
        delegatedStakingWithdrawalTimeLimit,
        services.rewards.delegatedRewards
      )
    }
  private val nodeCollateralsRoutes = HasherSelector[F].withCurrent { implicit hasher =>
    NodeCollateralRoutes[F](
      mkNodeCollateralCell,
      sharedValidators.updateNodeCollateralValidator,
      storages.globalSnapshot,
      storages.node,
      delegatedStakingWithdrawalTimeLimit
    )
  }

  private val walletRoutes = WalletRoutes[F, GlobalIncrementalSnapshot]("/dag", services.address)
  private val consensusInfoRoutes =
    HasherSelector[F].withCurrent { implicit hasher =>
      new ConsensusInfoRoutes[F, GlobalSnapshotKey, GlobalConsensusOutcome](services.cluster, services.consensus.storage, selfId)
    }
  private val consensusRoutes = services.consensus.routes.p2pRoutes

  private val debugRoutes = DebugRoutes[F](
    storages.cluster,
    services.consensus,
    services.gossip,
    services.session,
    DebugTrustRoutes[F](storages.trust).public
  ).publicRoutes

  private val metricRoutes = MetricRoutes[F]().publicRoutes
  private val targetRoutes =
    HasherSelector[F].withCurrent { implicit hasher =>
      TargetRoutes[F](services.cluster).publicRoutes
    }

//  implicit val metrics: Metrics[F] = implicitly[Metrics[F]]

  private val openRoutes: HttpRoutes[F] =
    CORS.policy.withAllowOriginAll.withAllowHeadersAll.withAllowCredentials(false).apply {
      MetricsMiddleware[F]()(implicitly[Async[F]], implicitly[Metrics[F]]) {
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
                trustRoutes.publicRoutes <+>
                allowSpendRoutes.publicRoutes <+>
                tokenLockRoutes.publicRoutes <+>
                nodeParametersRoutes.publicRoutes <+>
                delegatedStakesRoutes.publicRoutes <+>
                nodeCollateralsRoutes.publicRoutes
            }
          }
      }
    }

  private val p2pRoutes: HttpRoutes[F] =
    MetricsMiddleware[F]()(implicitly[Async[F]], implicitly[Metrics[F]]) {
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
                  consensusRoutes
              )
            )
          )
      )
    }

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
