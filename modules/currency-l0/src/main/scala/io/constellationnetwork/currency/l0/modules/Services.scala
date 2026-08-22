package io.constellationnetwork.currency.l0.modules

import java.security.KeyPair

import cats.Parallel
import cats.data.NonEmptySet
import cats.effect.std.{Random, Supervisor}
import cats.effect.{Async, IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.SortedSet

import io.constellationnetwork.currency.dataApplication.BaseDataApplicationL0Service
import io.constellationnetwork.currency.l0.config.types.AppConfig
import io.constellationnetwork.currency.l0.http.p2p.P2PClient
import io.constellationnetwork.currency.l0.infrastructure.snapshot.services.CurrencyMessagesService
import io.constellationnetwork.currency.l0.node.L0NodeContext
import io.constellationnetwork.currency.l0.snapshot._
import io.constellationnetwork.currency.l0.snapshot.services.{StateChannelBinarySender, StateChannelSnapshotService}
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.domain.allowance_list.AllowanceListEntry
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.json.{JsonBrotliBinarySerializer, JsonSerializer}
import io.constellationnetwork.kernel._
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, SharedConfig}
import io.constellationnetwork.node.shared.domain.cluster.services.{Cluster, Session}
import io.constellationnetwork.node.shared.domain.collateral.Collateral
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.healthcheck.LocalHealthcheck
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.domain.snapshot.services.{AddressService, GlobalL0Service}
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastNGlobalSnapshotStorage
import io.constellationnetwork.node.shared.domain.statechannel.FeeCalculator
import io.constellationnetwork.node.shared.infrastructure.collateral.Collateral
import io.constellationnetwork.node.shared.infrastructure.gossip.event.ChainTip
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
import io.constellationnetwork.node.shared.infrastructure.snapshot._
import io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency.DataApplicationSnapshotAcceptanceManager
import io.constellationnetwork.node.shared.infrastructure.snapshot.services.AddressService
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.LastNGlobalSnapshotStorage
import io.constellationnetwork.node.shared.modules.{SharedServices, SharedStorages, SharedValidators}
import io.constellationnetwork.node.shared.resources.ConsensusDispatcher
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.SharedArtifact
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security._
import io.constellationnetwork.security.signature.{Signed, SignedValidator}

import org.http4s.client.Client

object Services {

  def make[F[
    _
  ]: Async: Parallel: Random: JsonSerializer: KryoSerializer: SecurityProvider: HasherSelector: Metrics: Supervisor, R <: CliMethod](
    sharedCfg: SharedConfig,
    p2PClient: P2PClient[F],
    sharedServices: SharedServices[F, R],
    sharedStorages: SharedStorages[F],
    sharedValidators: SharedValidators[F],
    storages: Storages[F],
    client: Client[F],
    session: Session[F],
    seedlist: Option[Set[SeedlistEntry]],
    selfId: PeerId,
    keyPair: KeyPair,
    cfg: AppConfig,
    effectiveConsensusConfig: ConsensusConfig,
    maybeDataApplication: Option[BaseDataApplicationL0Service[F]],
    maybeRewards: Option[Rewards[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotEvent]],
    signedValidator: SignedValidator[F],
    globalSnapshotContextFns: GlobalSnapshotContextFunctions[F],
    maybeMajorityPeerIds: Option[NonEmptySet[PeerId]],
    hasherSelector: HasherSelector[F],
    stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]],
    customPeersAllowanceList: Option[Set[AllowanceListEntry]],
    mkCell: CurrencySnapshotEvent => Cell[F, StackF, _, Either[CellError, Ω], _],
    maybeCustomArtifacts: Option[Signed[CurrencyIncrementalSnapshot] => Option[SortedSet[SharedArtifact]]],
    queues: Queues[F],
    getPeerChainTips: F[Map[PeerId, ChainTip]],
    consensusDispatcher: Option[ConsensusDispatcher[F]] = None
  )(
    implicit globalStateProofSelector: GlobalStateProofSelector,
    currencyStateProofSelector: CurrencyStateProofSelector
  ): F[Services[F, R]] =
    for {
      implicit0(hasher: Hasher[F]) <- hasherSelector.getCurrent.pure[F]

      // Resolve a process death between recovery-outbox preparation and local Currency commit
      // before the sender is allowed to restore anything. A matching durable Currency artifact
      // promotes the exact binary; an absent artifact discards only the non-publishable intent;
      // a conflicting artifact fails startup closed.
      _ <- storages.recoverySyncPublication.reconcilePrepared { ordinal =>
        (storages.snapshot.getHashed(ordinal), storages.snapshotInfoLocalFileSystemStorage.read(ordinal)).tupled.flatMap {
          case (Some(artifact), Some(info)) =>
            CurrencySnapshotInfo
              .stateProofBuilder[F]
              .buildProof(info, ordinal)
              .map(proof => Option.when(proof === artifact.stateProof)(artifact))
          case _ => none[Hashed[CurrencyIncrementalSnapshot]].pure[F]
        }
      }

      stateChannelBinarySender <- StateChannelBinarySender.make(
        storages.identifier,
        storages.globalL0Cluster,
        storages.lastSyncGlobalSnapshot,
        p2PClient.stateChannelSnapshot,
        stateChannelAllowanceLists,
        selfId,
        cfg.environment,
        customPeersAllowanceList,
        storages.cluster,
        storages.recoverySyncPublication
      )

      l0NodeContext = L0NodeContext
        .make[F](storages.snapshot, hasherSelector, storages.lastSyncGlobalSnapshot, storages.identifier, seedlist)

      dataApplicationAcceptanceManager = (maybeDataApplication, storages.calculatedStateStorage).mapN {
        case (service, storage) =>
          DataApplicationSnapshotAcceptanceManager.make[F](
            service,
            l0NodeContext,
            storage,
            sharedCfg.fieldsAddedOrdinals.feeTransactionSecurityFor(sharedCfg.environment)
          )
      }

      feeCalculator = FeeCalculator.make(cfg.shared.feeConfigs)

      stateChannelSnapshotService <- StateChannelSnapshotService
        .make[F](
          keyPair,
          storages.snapshot,
          storages.incrementalSnapshotLocalFileSystemStorage,
          storages.snapshotInfoLocalFileSystemStorage,
          storages.lastSyncGlobalSnapshot,
          dataApplicationAcceptanceManager,
          stateChannelBinarySender,
          storages.lastGlobalSnapshotSync,
          storages.recoverySyncPublication,
          feeCalculator,
          cfg.snapshotSize
        )
        .pure[F]

      creator = CurrencySnapshotCreator.make[F](
        sharedCfg.fieldsAddedOrdinals.tessellation3Migration.getOrElse(sharedCfg.environment, SnapshotOrdinal.MinValue),
        sharedServices.currencySnapshotAcceptanceManager,
        dataApplicationAcceptanceManager,
        cfg.snapshotSize,
        sharedServices.currencyEventsCutter,
        storages.currencySnapshotEventValidationError,
        Some(storages.lastGlobalSnapshotSync.getRequiredRecoveryRefresh)
      )

      validator = CurrencySnapshotValidator.make[F](
        creator,
        signedValidator,
        maybeRewards,
        maybeDataApplication
      )

      addressService = AddressService.make[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](cfg.shared.addresses, storages.snapshot)
      collateralService = Collateral.make[F](cfg.collateral, storages.snapshot)
      globalL0Service = GlobalL0Service
        .make[F](
          p2PClient.l0GlobalSnapshot,
          storages.globalL0Cluster,
          storages.lastSyncGlobalSnapshot,
          None,
          maybeMajorityPeerIds,
          sharedStorages.mptStore
        )

      currencyMessagesService = CurrencyMessagesService.make[F](
        sharedValidators.currencyMessageValidator,
        storages.identifier,
        sharedStorages.lastGlobalSnapshot
      )

      currencyAddress <- storages.identifier.get

      consensus <- CurrencySnapshotConsensus
        .make[F](
          sharedServices.gossip,
          selfId,
          keyPair,
          seedlist,
          cfg.collateral.amount,
          storages.cluster,
          storages.node,
          storages.lastSyncGlobalSnapshot,
          storages.snapshot,
          storages.snapshotInfoLocalFileSystemStorage,
          currencyAddress,
          maybeRewards,
          cfg.snapshot,
          effectiveConsensusConfig,
          cfg.environment.entryName,
          client,
          session,
          stateChannelSnapshotService,
          maybeDataApplication,
          creator,
          validator,
          hasherSelector,
          sharedServices.restart,
          cfg.shared.leavingDelay,
          globalL0Service.pullGlobalSnapshot,
          maybeCustomArtifacts,
          storages.eventMempool,
          storages.lastGlobalSnapshotSync,
          queues.rumor,
          getPeerChainTips,
          sharedServices.localHealthMonitor,
          consensusDispatcher
        )
    } yield
      new Services[F, R](
        localHealthcheck = sharedServices.localHealthcheck,
        cluster = sharedServices.cluster,
        session = sharedServices.session,
        gossip = sharedServices.gossip,
        consensus = consensus,
        address = addressService,
        collateral = collateralService,
        stateChannelSnapshot = stateChannelSnapshotService,
        globalL0 = globalL0Service,
        snapshotContextFunctions = sharedServices.currencySnapshotContextFns,
        dataApplication = maybeDataApplication,
        globalSnapshotContextFunctions = globalSnapshotContextFns,
        stateChannelBinarySender = stateChannelBinarySender,
        restart = sharedServices.restart,
        currencyMessages = currencyMessagesService
      ) {}
}

sealed abstract class Services[F[_], R <: CliMethod] private (
  val localHealthcheck: LocalHealthcheck[F],
  val cluster: Cluster[F],
  val session: Session[F],
  val gossip: Gossip[F],
  val consensus: CurrencySnapshotConsensus[F],
  val address: AddressService[F, CurrencyIncrementalSnapshot],
  val collateral: Collateral[F],
  val stateChannelSnapshot: StateChannelSnapshotService[F],
  val globalL0: GlobalL0Service[F],
  val snapshotContextFunctions: CurrencySnapshotContextFunctions[F],
  val dataApplication: Option[BaseDataApplicationL0Service[F]],
  val globalSnapshotContextFunctions: GlobalSnapshotContextFunctions[F],
  val stateChannelBinarySender: StateChannelBinarySender[F],
  val restart: RestartService[F, R],
  val currencyMessages: CurrencyMessagesService[F]
)
