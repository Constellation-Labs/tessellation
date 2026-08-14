package io.constellationnetwork.dag.l0.modules

import java.security.KeyPair

import cats.Parallel
import cats.data.NonEmptySet
import cats.effect.kernel.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._

import io.constellationnetwork.dag.l0.config.types.AppConfig
import io.constellationnetwork.dag.l0.domain.cell.L0Cell
import io.constellationnetwork.dag.l0.domain.snapshot.recovery.{Gl0RecoveryPlanLoader, Gl0RecoveryPlanReceipt}
import io.constellationnetwork.dag.l0.domain.statechannel.StateChannelService
import io.constellationnetwork.dag.l0.infrastructure.mempool.GlobalEventMempool
import io.constellationnetwork.dag.l0.infrastructure.rewards._
import io.constellationnetwork.dag.l0.infrastructure.snapshot._
import io.constellationnetwork.dag.l0.infrastructure.snapshot.event.GlobalSnapshotEvent
import io.constellationnetwork.dag.l0.infrastructure.trust.TrustStorageUpdater
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.config.DefaultDelegatedRewardsConfigProvider
import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, SharedConfig}
import io.constellationnetwork.node.shared.domain.cluster.services.{Cluster, Session}
import io.constellationnetwork.node.shared.domain.collateral.Collateral
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.healthcheck.LocalHealthcheck
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.domain.snapshot.services.AddressService
import io.constellationnetwork.node.shared.infrastructure.collateral.MptStoreCollateral
import io.constellationnetwork.node.shared.infrastructure.delegatedStake.{RewardsInfoCalculator, RewardsInfoStorage}
import io.constellationnetwork.node.shared.infrastructure.gossip.event.{ChainTip, EventGossipClient, RecoveryPeerHint}
import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
import io.constellationnetwork.node.shared.infrastructure.snapshot.services.AddressService
import io.constellationnetwork.node.shared.logger.LoggerBundle
import io.constellationnetwork.node.shared.modules.{SharedServices, SharedStorages, SharedValidators}
import io.constellationnetwork.node.shared.resources.ConsensusDispatcher
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.mpt.GlobalStateKey
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, GlobalStateProofSelector}
import io.constellationnetwork.security.{Hasher, HasherSelector, SecurityProvider}

import org.http4s.client.Client
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Services {

  def make[F[
    _
  ]: Async: Parallel: Random: KryoSerializer: JsonSerializer: HasherSelector: SecurityProvider: Metrics: Supervisor, R <: CliMethod](
    sharedCfg: SharedConfig,
    sharedServices: SharedServices[F, R],
    sharedStorages: SharedStorages[F],
    queues: Queues[F],
    storages: Storages[F],
    validators: SharedValidators[F],
    client: Client[F],
    session: Session[F],
    seedlist: Option[Set[SeedlistEntry]],
    stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]],
    selfId: PeerId,
    keyPair: KeyPair,
    cfg: AppConfig,
    effectiveConsensusConfig: ConsensusConfig,
    txHasher: Hasher[F],
    loggerBundle: LoggerBundle[F],
    getPeerChainTips: F[Map[PeerId, ChainTip]],
    configuredRecoveryPlan: F[Option[Gl0RecoveryPlanLoader.Verified]],
    recoveryPlanReceipt: Gl0RecoveryPlanReceipt[F],
    initiallyHoldConsensusFirstRound: Boolean,
    consensusDispatcher: Option[ConsensusDispatcher[F]] = None
  )(
    implicit globalStateProofSelector: GlobalStateProofSelector
  ): F[Services[F, R]] =
    for {
      classicRewards <- Rewards
        .make[F](
          cfg.rewards,
          ProgramsDistributor.make,
          FacilitatorDistributor.make
        )
        .pure[F]

      delegatorRewards <- HasherSelector[F].withCurrent { implicit hasher =>
        GlobalDelegatedRewardsDistributor
          .make[F](
            cfg.environment,
            DefaultDelegatedRewardsConfigProvider.getConfig()
          )
          .pure[F]
      }

      rewardsInfoCalculator = RewardsInfoCalculator.make(delegatorRewards)

      rewardsInfoStorage <- RewardsInfoStorage.make

      rewardsService = RewardsService(
        classicRewards,
        delegatorRewards,
        rewardsInfoCalculator,
        rewardsInfoStorage
      )

      eventMempoolService <- HasherSelector[F].withCurrent { implicit hasher =>
        GlobalEventMempool.make[F](
          GlobalEventMempool.defaultConfig
        )
      }

      eventGossipClient = EventGossipClient.make[F, GlobalSnapshotEvent](client, session)

      consensus <- HasherSelector[F].withCurrent { implicit hs =>
        GlobalSnapshotConsensus
          .make[F, R](
            sharedCfg,
            sharedServices.gossip,
            selfId,
            keyPair,
            seedlist,
            cfg.collateral.amount,
            storages.cluster,
            storages.node,
            storages.globalSnapshot,
            validators,
            sharedServices,
            cfg,
            effectiveConsensusConfig,
            stateChannelPullDelay = cfg.stateChannel.pullDelay,
            stateChannelPurgeDelay = cfg.stateChannel.purgeDelay,
            stateChannelAllowanceLists,
            feeConfigs = cfg.shared.feeConfigs,
            client,
            session,
            rewardsService,
            txHasher,
            sharedServices.restart,
            sharedStorages.lastNGlobalSnapshot,
            sharedStorages.lastGlobalSnapshot,
            storages.globalSnapshot.getHashed,
            sharedStorages.mptStore,
            eventMempoolService,
            eventGossipClient,
            loggerBundle,
            queues.rumor,
            getPeerChainTips,
            configuredRecoveryPlan,
            recoveryPlanReceipt,
            initiallyHoldConsensusFirstRound,
            // Activate the Cluster.leave() wedge guard: AbandonmentTracker writes wedge state
            // into the SharedServices-owned Ref; Cluster reads from the same Ref via the
            // consensusHealth thunk passed at Cluster.make time.
            injectedHealthRef = Some(sharedServices.consensusHealthRef),
            consensusDispatcher = consensusDispatcher
          )
      }
      addressService = AddressService.make[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
        cfg.shared.addresses,
        storages.globalSnapshot,
        Some(sharedStorages.mptStore)
      )
      collateralService = MptStoreCollateral.make[F](cfg.collateral, sharedStorages.mptStore)
      stateChannelService = StateChannelService
        .make[F](
          L0Cell.mkL0Cell(
            queues.l1Output,
            queues.stateChannelOutput,
            queues.updateNodeParametersOutput,
            queues.delegatedStakeOutput,
            queues.nodeCollateralOutput
          ),
          validators.stateChannelValidator,
          sharedStorages.mptStore
        )
      getOrdinal = storages.globalSnapshot.headSnapshot.map(_.map(_.ordinal))
      trustUpdaterService = TrustStorageUpdater.make(getOrdinal, sharedServices.gossip, storages.trust)
      recoveryPeerHintService <- RecoveryPeerHint.make[F]
    } yield
      new Services[F, R](
        localHealthcheck = sharedServices.localHealthcheck,
        cluster = sharedServices.cluster,
        session = sharedServices.session,
        gossip = sharedServices.gossip,
        consensus = consensus,
        address = addressService,
        collateral = collateralService,
        stateChannel = stateChannelService,
        trustStorageUpdater = trustUpdaterService,
        restart = sharedServices.restart,
        rewards = rewardsService,
        recoveryPeerHint = recoveryPeerHintService,
        eventMempool = eventMempoolService
      ) {}
}

sealed abstract class Services[F[_], R <: CliMethod] private (
  val localHealthcheck: LocalHealthcheck[F],
  val cluster: Cluster[F],
  val session: Session[F],
  val gossip: Gossip[F],
  val consensus: GlobalSnapshotConsensus[F],
  val address: AddressService[F, GlobalIncrementalSnapshot],
  val collateral: Collateral[F],
  val stateChannel: StateChannelService[F],
  val trustStorageUpdater: TrustStorageUpdater[F],
  val restart: RestartService[F, R],
  val rewards: RewardsService[F],
  val recoveryPeerHint: RecoveryPeerHint[F],
  val eventMempool: EventMempool[F, GlobalSnapshotEvent, GlobalStateKey]
)
