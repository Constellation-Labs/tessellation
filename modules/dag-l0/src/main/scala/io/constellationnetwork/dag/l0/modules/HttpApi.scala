package io.constellationnetwork.dag.l0.modules

import java.security.PrivateKey

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.dag.l0.domain.cell.{L0Cell, L0CellInput}
import io.constellationnetwork.dag.l0.domain.delegatedStake.DelegatedStakeOutput
import io.constellationnetwork.dag.l0.domain.nodeCollateral.NodeCollateralOutput
import io.constellationnetwork.dag.l0.http.routes._
import io.constellationnetwork.dag.l0.infrastructure.snapshot.GlobalSnapshotKey
import io.constellationnetwork.dag.l0.infrastructure.snapshot.event.GlobalSnapshotEvent
import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema.GlobalConsensusOutcome
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.env.AppEnvironment._
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.config.types.{HttpConfig, RouteRateLimiterConfig, SharedConfig}
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.node.shared.http.p2p.middlewares.{MetricsMiddleware, PeerAuthMiddleware, `X-Id-Middleware`}
import io.constellationnetwork.node.shared.http.routes._
import io.constellationnetwork.node.shared.infrastructure.gossip.event.ChainTip
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.CombinedSnapshotCheckpointFileSystemStorage
import io.constellationnetwork.node.shared.modules.SharedValidators
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.mpt.GlobalStateKey
import io.constellationnetwork.schema.node.UpdateNodeParameters
import io.constellationnetwork.schema.peer.{PeerCommitteeStatus, PeerCommitteeView, PeerId}
import io.constellationnetwork.schema.semver.TessellationVersion
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{HasherSelector, SecurityProvider}

import eu.timepit.refined.auto._
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
    delegatedStakingWithdrawalTimeLimit: EpochProgress,
    sharedConfig: SharedConfig,
    combinedSnapshotCheckpointFileSystemStorage: CombinedSnapshotCheckpointFileSystemStorage[
      F,
      GlobalIncrementalSnapshot,
      GlobalSnapshotInfo
    ],
    lastNGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    getLocalChainTip: Option[F[Option[ChainTip]]] = None,
    maybeMarkSeen: Option[Hash => F[Unit]] = None
  ): F[HttpApi[F, R]] =
    SnapshotRoutes
      .make[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
        storages.globalSnapshot,
        lastNGlobalSnapshotStorage.some,
        storages.fullGlobalSnapshot.some,
        "/global-snapshots",
        storages.node,
        HasherSelector[F],
        sharedConfig.snapshotTimeoutsConfig,
        combinedSnapshotCheckpointFileSystemStorage,
        sharedConfig.snapshotServingConfig,
        httpCfg.externalIp.toString.some
      )
      .map { snapshotRoutes =>
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
          delegatedStakingWithdrawalTimeLimit,
          sharedConfig,
          snapshotRoutes,
          getLocalChainTip,
          maybeMarkSeen
        ) {}
      }
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
  delegatedStakingWithdrawalTimeLimit: EpochProgress,
  sharedConfig: SharedConfig,
  snapshotRoutes: SnapshotRoutes[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
  getLocalChainTip: Option[F[Option[ChainTip]]] = None,
  maybeMarkSeen: Option[Hash => F[Unit]] = None
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

  // Per-request committee-view lookup. Builds a PeerCommitteeView for every peer present in the
  // latest ConsensusOutcome's peerQuality OR readmissionCountdown maps. Read at HTTP-handler
  // time → reflects the most recently finalized round. Returns empty map until the first round
  // finalizes.
  //
  // TODO(cleanup-pass): extract the chronic-classifier predicate to a shared helper called by
  // BOTH GlobalSnapshotConsensusStateCreator:255 AND this lookup. Today the threshold logic is
  // duplicated here as hard-coded defaults to avoid plumbing ConsensusConfig through
  // HttpApi.make. The defaults below match the production ConsensusConfig defaults (see
  // node-shared/.../config/types.scala — minObservationHistoryFloor=30, minParticipationRatio=0.5).
  // If an operator tunes consensus thresholds away from defaults, the served `status` field
  // drifts from the consensus-side decision until the refactor lands. `completed`,
  // `participated`, `ratio`, `probationRoundsRemaining` are always authoritative regardless.
  // Shape of the cleanup: PeerQualityClassifier.isChronic(completed, participated, config),
  // plus passing ConsensusConfig through HttpApi.make → Main.scala wires cfg.snapshot.consensus.
  private val ChronicMinObservationHistoryFloor: Int = 30
  private val ChronicMinParticipationRatio: Double = 0.5

  private val getCommitteeView: F[Map[PeerId, PeerCommitteeView]] =
    services.consensus.storage.getLastConsensusOutcome.map {
      case None => Map.empty[PeerId, PeerCommitteeView]
      case Some(outcome) =>
        val probation = outcome.readmissionCountdown
        val all = outcome.peerQuality.keySet ++ probation.keySet
        all.iterator.map { pid =>
          val (completed, participated) = outcome.peerQuality.getOrElse(pid, (0, 0))
          val ratio = if (participated > 0) completed.toDouble / participated.toDouble else 1.0
          val isChronic =
            participated >= ChronicMinObservationHistoryFloor &&
              ratio < ChronicMinParticipationRatio
          val onProbation = probation.contains(pid)
          val status: PeerCommitteeStatus =
            if (onProbation) PeerCommitteeStatus.Probation
            else if (isChronic) PeerCommitteeStatus.Chronic
            else PeerCommitteeStatus.Active
          pid -> PeerCommitteeView(
            status = status,
            completed = completed,
            participated = participated,
            ratio = ratio,
            probationRoundsRemaining = if (onProbation) probation.get(pid) else None
          )
        }.toMap
    }

  private val clusterRoutes =
    HasherSelector[F].withCurrent { implicit hasher =>
      ClusterRoutes[F](
        programs.joining,
        programs.peerDiscovery,
        storages.cluster,
        services.cluster,
        services.collateral,
        getCommitteeView = Some(getCommitteeView)
      )
    }
  private val nodeRoutes = NodeRoutes[F](storages.node, storages.session, storages.cluster, nodeVersion, httpCfg, selfId)

  private val registrationRoutes = RegistrationRoutes[F](services.cluster)
  private val gossipRoutes = GossipRoutes[F](storages.rumor, services.gossip, sharedConfig.gossip.timeouts)
  private val eventGossipRoutes = EventGossipRoutes.make[F, GlobalSnapshotEvent, GlobalStateKey](
    services.eventMempool,
    getLocalChainTip,
    maybeMarkSeen
  )
  private val trustRoutes = TrustRoutes[F](storages.trust, programs.trustPush)
  private val stateChannelRoutes =
    HasherSelector[F].withCurrent { implicit hasher =>
      StateChannelRoutes[F](services.stateChannel, storages.globalSnapshot, sharedConfig.snapshotBinarySenderTimeouts)
    }
  private val dagRoutes = DAGBlockRoutes[F](mkDagCell, storages.globalSnapshot)
  private val allowSpendRoutes = AllowSpendBlockRoutes[F](queues.l1AllowSpendOutput)
  private val tokenLockBlockRoutes = TokenLockBlockRoutes[F](queues.l1TokenLockOutput)
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
        services.rewards.rewardsInfoStorage,
        storages.mptStore
      )
    }
  private val nodeCollateralsRoutes = HasherSelector[F].withCurrent { implicit hasher =>
    NodeCollateralRoutes[F](
      mkNodeCollateralCell,
      sharedValidators.updateNodeCollateralValidator,
      storages.globalSnapshot,
      storages.node,
      delegatedStakingWithdrawalTimeLimit,
      storages.mptStore
    )
  }
  private val tokenLockRoutes = GL0TokenLockRoutes(storages.globalSnapshot, storages.mptStore)

  private val walletRoutes = WalletRoutes[F, GlobalIncrementalSnapshot]("/dag", services.address)
  private val consensusInfoRoutes =
    HasherSelector[F].withCurrent { implicit hasher =>
      new ConsensusInfoRoutes[F, GlobalSnapshotKey, GlobalConsensusOutcome](
        services.cluster,
        services.consensus.storage,
        selfId,
        services.consensus.healthRef
      )
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
                tokenLockBlockRoutes.publicRoutes <+>
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
                  eventGossipRoutes.p2pRoutes <+>
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
